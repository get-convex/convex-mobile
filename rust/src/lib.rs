use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use async_once_cell::OnceCell;
use convex::{
    AuthTokenFetcher, AuthenticationToken, ConvexClient, ConvexClientBuilder, FunctionResult,
    OptimisticLocalStore, OptimisticQuery, Value, WebSocketState,
};
use futures::{
    channel::oneshot::{self, Sender},
    pin_mut, select_biased, FutureExt, StreamExt,
};
use parking_lot::Mutex;
use tokio::sync::mpsc;
use tracing::{debug, info};

mod logging;

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    /// An error that occurs internally here in the mobile Convex client.
    #[error("InternalError: {msg}")]
    InternalError { msg: String },
    /// An application specific error that is thrown in a remote Convex backend
    /// function.
    #[error("ConvexError: {data}")]
    ConvexError { data: String },
    /// An unexpected server error that is thrown in a remote Convex backend
    /// function.
    #[error("ServerError: {msg}")]
    ServerError { msg: String },
}

impl From<anyhow::Error> for ClientError {
    fn from(value: anyhow::Error) -> Self {
        Self::InternalError {
            msg: value.to_string(),
        }
    }
}

pub trait QuerySubscriber: Send + Sync {
    fn on_update(&self, value: String) -> ();

    fn on_error(&self, message: String, value: Option<String>) -> ();
}

pub trait WebSocketStateSubscriber: Send + Sync {
    fn on_state_change(&self, state: WebSocketState) -> ();
}

#[async_trait::async_trait]
pub trait AuthTokenProvider: Send + Sync {
    async fn fetch_token(&self, force_refresh: bool) -> Result<Option<String>, ClientError>;
}

/// An optimistic update implemented by the foreign client.
///
/// See [convex::OptimisticUpdate]. `apply` may be called multiple times, runs
/// on the client's worker thread, and must be fast and synchronous: it must not
/// block or call back into the client. If it returns an error, its writes are
/// undone and the mutation isn't sent (or, when replayed on new server data,
/// the update is dropped).
pub trait MobileOptimisticUpdate: Send + Sync {
    fn apply(&self, store: Arc<MobileOptimisticLocalStore>) -> Result<(), ClientError>;
}

/// A query result as seen by an optimistic update. Args and value are JSON
/// encoded Convex values; the value is [None] if the query is loading.
pub struct MobileOptimisticQuery {
    pub args: HashMap<String, String>,
    pub value: Option<String>,
}

/// Exposes an [OptimisticLocalStore] to a [MobileOptimisticUpdate].
///
/// The store is only usable while [MobileOptimisticUpdate::apply] runs.
pub struct MobileOptimisticLocalStore {
    store: Mutex<Option<OptimisticLocalStore>>,
}

impl MobileOptimisticLocalStore {
    /// Read the result of a query as JSON, or [None] if it's not loaded.
    pub fn get_query(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<Option<String>, ClientError> {
        self.with_store(|store| {
            store
                .get_query(name.parse()?, try_parse_json_args(args)?)
                .map(value_to_json)
                .transpose()
        })
    }

    /// Read the args and results of all queries with the given name.
    pub fn get_all_queries(&self, name: String) -> Result<Vec<MobileOptimisticQuery>, ClientError> {
        self.with_store(|store| {
            store
                .get_all_queries(name.parse()?)
                .into_iter()
                .map(|OptimisticQuery { args, value }| {
                    Ok(MobileOptimisticQuery {
                        args: args
                            .into_iter()
                            .map(|(k, v)| Ok((k, value_to_json(v)?)))
                            .collect::<anyhow::Result<_>>()?,
                        value: value.map(value_to_json).transpose()?,
                    })
                })
                .collect()
        })
    }

    /// Set the result of a query to a JSON value, or to loading with [None].
    pub fn set_query(
        &self,
        name: String,
        args: HashMap<String, String>,
        value: Option<String>,
    ) -> Result<(), ClientError> {
        self.with_store(|store| {
            let value = value.map(|v| json_to_value(&v)).transpose()?;
            store.set_query(name.parse()?, try_parse_json_args(args)?, value);
            Ok(())
        })
    }

    fn with_store<T>(
        &self,
        f: impl FnOnce(&mut OptimisticLocalStore) -> anyhow::Result<T>,
    ) -> Result<T, ClientError> {
        let mut store = self.store.lock();
        let store = store.as_mut().ok_or_else(|| ClientError::InternalError {
            msg: "OptimisticLocalStore used after its optimistic update returned".into(),
        })?;
        Ok(f(store)?)
    }
}

/// Runs `optimistic_update` against `store`, lending the store to the foreign
/// client for the duration of the call.
fn apply_optimistic_update(
    optimistic_update: &dyn MobileOptimisticUpdate,
    store: &mut OptimisticLocalStore,
) -> anyhow::Result<()> {
    let lent = Arc::new(MobileOptimisticLocalStore {
        store: Mutex::new(Some(std::mem::take(store))),
    });
    // The store must come back even if `apply` panics (UniFFI turns unexpected
    // foreign exceptions into panics), so convex can undo the update's writes.
    let _return_store = ReturnLentStore {
        target: store,
        lent: lent.clone(),
    };
    optimistic_update.apply(lent)?;
    Ok(())
}

struct ReturnLentStore<'a> {
    target: &'a mut OptimisticLocalStore,
    lent: Arc<MobileOptimisticLocalStore>,
}

impl Drop for ReturnLentStore<'_> {
    fn drop(&mut self) {
        if let Some(store) = self.lent.store.lock().take() {
            *self.target = store;
        }
    }
}

pub struct SubscriptionHandle {
    cancel_sender: Mutex<Option<Sender<()>>>,
}

impl SubscriptionHandle {
    pub fn new(cancel_sender: Sender<()>) -> Self {
        SubscriptionHandle {
            cancel_sender: Mutex::new(Some(cancel_sender)),
        }
    }

    pub fn cancel(&self) {
        if let Some(sender) = self.cancel_sender.lock().take() {
            // Ignore send failure — receiver already dropped means subscription is already cancelled.
            let _ = sender.send(());
        }
    }
}

/// Initializes logging.
///
/// Call this early in the life of your application to enable logging from
/// [MobileConvexClient] and its dependencies.
pub fn init_convex_logging() {
    use std::sync::Once;
    static INIT: Once = Once::new();

    INIT.call_once(|| {
        logging::init_logging();
        info!("convexmobile logging initialized");
    });
}

/// A wrapper around a [ConvexClient] and a [tokio::runtime::Runtime] used to
/// asynchronously call Convex functions.
///
/// That enables easy async communication for mobile clients. They can call the
/// various methods on [MobileConvexClient] and await results without blocking
/// their main threads.
struct MobileConvexClient {
    deployment_url: String,
    client_id: String,
    web_socket_state_subscriber: Option<Arc<dyn WebSocketStateSubscriber>>,
    client: OnceCell<ConvexClient>,
    rt: tokio::runtime::Runtime,
}

impl MobileConvexClient {
    /// Creates a new [MobileConvexClient].
    ///
    /// The internal [ConvexClient] doesn't get created/connected until the
    /// first public method call that hits the Convex backend.
    ///
    /// The `client_id` should be a string representing the name and version of
    /// the foreign client.
    pub fn new(
        deployment_url: String,
        client_id: String,
        web_socket_state_subscriber: Option<Arc<dyn WebSocketStateSubscriber>>,
    ) -> MobileConvexClient {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();
        MobileConvexClient {
            deployment_url,
            client_id,
            web_socket_state_subscriber,
            client: OnceCell::new(),
            rt,
        }
    }

    /// Returns a connected [ConvexClient].
    ///
    /// The first call is guaranteed to create the client object and subsequent
    /// calls will return clones of that connected client.
    ///
    /// Returns an error if ...
    /// TODO figure out reasons.
    async fn connected_client(&self) -> anyhow::Result<ConvexClient> {
        let url = self.deployment_url.clone();

        self.client
            .get_or_try_init(async {
                let client_id = self.client_id.to_owned();
                let (tx, mut rx) = mpsc::channel(1);
                let possible_subscriber = self.web_socket_state_subscriber.clone();
                if let Some(subscriber) = possible_subscriber.clone() {
                    self.rt.spawn(async move {
                        while let Some(state) = rx.recv().await {
                            subscriber.on_state_change(state);
                        }
                    });
                }

                let has_subscriber = possible_subscriber.is_some();
                self.rt
                    .spawn(async move {
                        let mut builder =
                            ConvexClientBuilder::new(url.as_str()).with_client_id(&client_id);
                        if has_subscriber {
                            builder = builder.with_on_state_change(tx);
                        }
                        builder.build().await
                    })
                    .await?
            })
            .await
            .map(|client_ref| client_ref.clone())
    }

    /// Execute a one-shot query against the Convex backend.
    pub async fn query(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        let mut client = self.connected_client().await?;
        let result = client.query(name.as_str(), parse_json_args(args)).await?;
        handle_direct_function_result(result)
    }

    /// Subscribe to updates to a query against the Convex backend.
    ///
    /// The [QuerySubscriber] will be called back with initial query results and
    /// it will continue to get called as the underlying data changes.
    ///
    /// The returned [SubscriptionHandle] can be used to cancel the
    /// subscription.
    pub async fn subscribe(
        &self,
        name: String,
        args: HashMap<String, String>,
        subscriber: Arc<dyn QuerySubscriber>,
    ) -> Result<Arc<SubscriptionHandle>, ClientError> {
        Ok(self.internal_subscribe(name, args, subscriber).await?)
    }

    async fn internal_subscribe(
        &self,
        name: String,
        args: HashMap<String, String>,
        subscriber: Arc<dyn QuerySubscriber>,
    ) -> anyhow::Result<Arc<SubscriptionHandle>> {
        let mut client = self.connected_client().await?;
        debug!("New subscription to {}", name);
        let mut subscription = client
            .subscribe(name.as_str(), parse_json_args(args))
            .await?;
        let (cancel_sender, cancel_receiver) = oneshot::channel::<()>();
        self.rt.spawn(async move {
            let cancel_fut = cancel_receiver.fuse();
            pin_mut!(cancel_fut);
            loop {
                select_biased! {
                    new_val = subscription.next().fuse() => {
                        match new_val {
                            Some(FunctionResult::Value(value)) => {
                                subscriber.on_update(serde_json::to_string(
                                    &serde_json::Value::from(value)
                                ).unwrap())
                            },
                            Some(FunctionResult::ErrorMessage(message)) => {
                                subscriber.on_error(message, None)
                            },
                            Some(FunctionResult::ConvexError(error)) => subscriber.on_error(
                                error.message,
                                Some(serde_json::ser::to_string(
                                    &serde_json::Value::from(error.data)
                                ).unwrap())
                            ),
                            None => {
                                debug!("Client dropped prematurely");
                                break
                            }
                        }
                    },
                    _ = cancel_fut => {
                        break
                    },
                }
            }
            debug!("Subscription canceled");
        });
        Ok(Arc::new(SubscriptionHandle::new(cancel_sender)))
    }

    /// Run a mutation against the Convex backend.
    pub async fn mutation(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        debug!("Running mutation: {}", name);
        let result = self.internal_mutation(name, args).await?;

        handle_direct_function_result(result)
    }

    async fn internal_mutation(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> anyhow::Result<FunctionResult> {
        let mut client = self.connected_client().await?;

        let result = self
            .rt
            .spawn(async move { client.mutation(&name, parse_json_args(args)).await })
            .await?;
        result
    }

    /// Run a mutation against the Convex backend, applying
    /// `optimistic_update` to local query results while it's in flight.
    pub async fn mutation_with_optimistic_update(
        &self,
        name: String,
        args: HashMap<String, String>,
        optimistic_update: Arc<dyn MobileOptimisticUpdate>,
    ) -> Result<String, ClientError> {
        debug!("Running mutation with optimistic update: {}", name);
        let mut client = self.connected_client().await?;
        let result = self
            .rt
            .spawn(async move {
                client
                    .mutation_with_optimistic_update(&name, parse_json_args(args), move |store| {
                        apply_optimistic_update(optimistic_update.as_ref(), store)
                    })
                    .await
            })
            .await
            .map_err(anyhow::Error::from)??;
        handle_direct_function_result(result)
    }

    /// Run an action on the Convex backend.
    pub async fn action(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        debug!("Running action: {}", name);
        let result = self.internal_action(name, args).await?;
        handle_direct_function_result(result)
    }

    async fn internal_action(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> anyhow::Result<FunctionResult> {
        let mut client = self.connected_client().await?;
        self.rt
            .spawn(async move { client.action(&name, parse_json_args(args)).await })
            .await?
    }

    /// Provide an OpenID Connect ID token to be associated with this client.
    ///
    /// Doing so will share that information with the Convex backend and a valid
    /// token will give the backend knowledge of a logged in user.
    ///
    /// Passing [None] for the token will disassociate a previous token,
    /// effectively returning to a logged out state.
    pub async fn set_auth(&self, token: Option<String>) -> Result<(), ClientError> {
        Ok(self.internal_set_auth(token).await?)
    }

    async fn internal_set_auth(&self, token: Option<String>) -> anyhow::Result<()> {
        let mut client = self.connected_client().await?;
        self.rt
            .spawn(async move { client.set_auth(token).await })
            .await
            .map_err(|e| e.into())
    }

    /// Set an auth token fetcher callback.
    ///
    /// The callback is invoked immediately and again on every websocket
    /// reconnect, allowing dynamic token refresh.
    ///
    /// Passing [None] clears the callback and logs out.
    pub async fn set_auth_callback(
        &self,
        provider: Option<Arc<dyn AuthTokenProvider>>,
    ) -> Result<(), ClientError> {
        Ok(self.internal_set_auth_callback(provider).await?)
    }

    async fn internal_set_auth_callback(
        &self,
        provider: Option<Arc<dyn AuthTokenProvider>>,
    ) -> anyhow::Result<()> {
        let mut client = self.connected_client().await?;
        let fetcher: Option<AuthTokenFetcher> = provider.map(|p| -> AuthTokenFetcher {
            Box::new(move |force_refresh: bool| {
                let p = p.clone();
                Box::pin(async move {
                    match p.fetch_token(force_refresh).await {
                        Ok(Some(token)) => Ok(AuthenticationToken::User(token)),
                        Ok(None) => Ok(AuthenticationToken::None),
                        Err(e) => Err(anyhow::anyhow!("{e}")),
                    }
                })
            })
        });
        self.rt
            .spawn(async move { client.set_auth_callback(fetcher).await })
            .await
            .map_err(|e| e.into())
    }
}

fn parse_json_args(raw_args: HashMap<String, String>) -> BTreeMap<String, Value> {
    raw_args
        .into_iter()
        .map(|(k, v)| {
            (
                k,
                Value::try_from(
                    serde_json::from_str::<serde_json::Value>(&v)
                        .expect("Invalid JSON data from FFI"),
                )
                .expect("Invalid Convex data from FFI"),
            )
        })
        .collect()
}

fn try_parse_json_args(
    raw_args: HashMap<String, String>,
) -> anyhow::Result<BTreeMap<String, Value>> {
    raw_args
        .into_iter()
        .map(|(k, v)| Ok((k, json_to_value(&v)?)))
        .collect()
}

fn json_to_value(json: &str) -> anyhow::Result<Value> {
    Value::try_from(serde_json::from_str::<serde_json::Value>(json)?)
}

fn value_to_json(value: Value) -> anyhow::Result<String> {
    Ok(serde_json::to_string(&serde_json::Value::from(value))?)
}

fn handle_direct_function_result(result: FunctionResult) -> Result<String, ClientError> {
    match result {
        FunctionResult::Value(v) => serde_json::to_string(&serde_json::Value::from(v))
            .map_err(|e| ClientError::InternalError { msg: e.to_string() }),
        FunctionResult::ConvexError(e) => Err(ClientError::ConvexError {
            data: serde_json::ser::to_string(&serde_json::Value::from(e.data)).unwrap(),
        }),
        FunctionResult::ErrorMessage(msg) => Err(ClientError::ServerError { msg }),
    }
}

uniffi::include_scaffolding!("convex-mobile");

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use convex::Value;
    use maplit::btreemap;

    use std::sync::Arc;

    use convex::OptimisticLocalStore;

    use crate::{
        apply_optimistic_update, parse_json_args, ClientError, MobileOptimisticLocalStore,
        MobileOptimisticUpdate,
    };

    #[test]
    fn test_boolean_values_in_json_args() {
        let mut m = HashMap::new();
        m.insert(String::from("a"), String::from("false"));

        assert_eq!(
            parse_json_args(m).get(&String::from("a")),
            Some(&Value::Boolean(false))
        )
    }

    #[test]
    fn test_number_values_in_json_args() {
        let mut m = HashMap::new();
        m.insert(String::from("a"), String::from("42"));
        m.insert(String::from("b"), String::from("42.42"));

        let result = parse_json_args(m);
        assert_eq!(result.get(&String::from("a")), Some(&Value::Float64(42.0)));
        assert_eq!(result.get(&String::from("b")), Some(&Value::Float64(42.42)))
    }

    #[test]
    fn test_list_values_in_json_args() {
        let mut m = HashMap::new();
        m.insert(String::from("a"), String::from("[1,2,3]"));
        m.insert(String::from("b"), String::from("[\"a\",\"b\",\"c\"]"));

        let result = parse_json_args(m);
        assert_eq!(
            result.get(&String::from("a")),
            Some(&Value::Array(vec![
                Value::Float64(1.0),
                Value::Float64(2.0),
                Value::Float64(3.0)
            ]))
        );
        assert_eq!(
            result.get(&String::from("b")),
            Some(&Value::Array(vec![
                Value::String(String::from("a")),
                Value::String(String::from("b")),
                Value::String(String::from("c"))
            ]))
        );
    }

    #[test]
    fn test_object_values_in_json_args() {
        let mut m = HashMap::new();
        m.insert(String::from("a"), String::from("{\"a\":1,\"b\":\"foo\"}"));

        let result = parse_json_args(m);
        assert_eq!(
            result.get(&String::from("a")),
            Some(&Value::Object(btreemap! {
                String::from("a") => Value::Float64(1.0),
                String::from("b") => Value::String(String::from("foo")),
            }))
        );
    }

    struct AppendMessage {
        leaked_store: parking_lot::Mutex<Option<Arc<MobileOptimisticLocalStore>>>,
    }

    impl MobileOptimisticUpdate for AppendMessage {
        fn apply(&self, store: Arc<MobileOptimisticLocalStore>) -> Result<(), ClientError> {
            let args = HashMap::from([(String::from("channel"), String::from("\"a\""))]);
            let current = store
                .get_query("messages:list".into(), args.clone())
                .unwrap()
                .unwrap_or_else(|| String::from("[]"));
            let mut messages: Vec<serde_json::Value> = serde_json::from_str(&current).unwrap();
            messages.push(serde_json::json!("hi"));
            store
                .set_query(
                    "messages:list".into(),
                    args,
                    Some(serde_json::to_string(&messages).unwrap()),
                )
                .unwrap();
            *self.leaked_store.lock() = Some(store);
            Ok(())
        }
    }

    #[test]
    fn test_apply_optimistic_update_round_trips_json() {
        let update = AppendMessage {
            leaked_store: parking_lot::Mutex::new(None),
        };
        let mut store = OptimisticLocalStore::default();
        apply_optimistic_update(&update, &mut store).unwrap();
        apply_optimistic_update(&update, &mut store).unwrap();

        let all = store.get_all_queries("messages:list".parse().unwrap());
        assert_eq!(all.len(), 1);
        assert_eq!(
            all[0].value,
            Some(Value::Array(vec![
                Value::String("hi".into()),
                Value::String("hi".into())
            ]))
        );

        // The store can't be used once the update has returned.
        let leaked_store = update.leaked_store.lock().take().unwrap();
        assert!(leaked_store
            .get_all_queries("messages:list".into())
            .is_err());
    }

    struct SetThenFail {
        panic: bool,
    }

    impl MobileOptimisticUpdate for SetThenFail {
        fn apply(&self, store: Arc<MobileOptimisticLocalStore>) -> Result<(), ClientError> {
            store.set_query("messages:list".into(), HashMap::new(), Some("[]".into()))?;
            if self.panic {
                panic!("foreign exception");
            }
            Err(ClientError::InternalError {
                msg: "bad update".into(),
            })
        }
    }

    #[test]
    fn test_store_is_returned_when_update_fails() {
        for panic in [false, true] {
            let mut store = OptimisticLocalStore::default();
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                apply_optimistic_update(&SetThenFail { panic }, &mut store)
            }));
            match result {
                Ok(result) => assert!(!panic && result.is_err()),
                Err(_) => assert!(panic),
            }
            // The store came back with the update's write, so convex can undo it.
            assert_eq!(
                store.get_all_queries("messages:list".parse().unwrap()).len(),
                1
            );
        }
    }
}
