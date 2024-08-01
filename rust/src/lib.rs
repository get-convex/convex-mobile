use android_logger::Config;
use anyhow::anyhow;
use async_once_cell::OnceCell;
use convex::{ConvexClient, FunctionResult, Value};
use futures::channel::oneshot::{self, Sender};
use futures::{pin_mut, select_biased, FutureExt, StreamExt};
use log::debug;
use log::LevelFilter;
use parking_lot::Mutex;

use std::collections::BTreeMap;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::task::JoinError;

#[derive(Debug, thiserror::Error)]
#[error("{e:?}")]
struct ClientError {
    e: anyhow::Error,
}

impl ClientError {
    fn message(&self) -> String {
        self.e.to_string()
    }
}

impl From<anyhow::Error> for ClientError {
    fn from(e: anyhow::Error) -> Self {
        Self { e }
    }
}

impl From<JoinError> for ClientError {
    fn from(_: JoinError) -> Self {
        Self {
            e: anyhow!("join error"),
        }
    }
}

impl From<serde_json::Error> for ClientError {
    fn from(_: serde_json::Error) -> Self {
        Self {
            e: anyhow!("JSON error"),
        }
    }
}

pub trait QuerySubscriber: Send + Sync {
    fn on_update(&self, value: String) -> ();

    fn on_error(&self, message: String, value: Option<String>) -> ();
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
            sender.send(()).unwrap();
        }
    }
}

/// A wrapper around a [ConvexClient] and a [tokio::runtime::Runtime] used to asynchronously call
/// Convex functions.
///
/// That enables easy async communication for mobile clients. They can call the various methods on
/// [MobileConvexClient] and await results without blocking their main threads.
struct MobileConvexClient {
    deployment_url: String,
    client: OnceCell<ConvexClient>,
    rt: tokio::runtime::Runtime,
}

impl MobileConvexClient {
    /// Creates a new [MobileConvexClient].
    ///
    /// The internal [ConvexClient] doesn't get created/connected until the first public method call that
    /// hits the Convex backend.
    pub fn new(deployment_url: String) -> MobileConvexClient {
        android_logger::init_once(Config::default().with_max_level(LevelFilter::Trace));
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();
        MobileConvexClient {
            deployment_url: deployment_url,
            client: OnceCell::new(),
            rt: rt,
        }
    }

    /// Returns a connected [ConvexClient].
    ///
    /// The first call is guaranteed to create the client object and subsequent calls will return
    /// clones of that connected client.
    ///
    /// Returns an error if ...
    /// TODO figure out reasons.
    async fn connected_client(&self) -> anyhow::Result<ConvexClient> {
        let url = self.deployment_url.clone();

        self.client
            .get_or_try_init(async {
                self.rt
                    .spawn(async move { ConvexClient::new(url.as_str()).await })
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
        debug!("got the client");
        let result = self
            .rt
            .spawn(async move {
                Ok::<FunctionResult, ClientError>(
                    client
                        .subscribe(name.as_str(), parse_json_args(args))
                        .await?
                        .next()
                        .await
                        .expect("INTERNAL BUG: Convex Client dropped prematurely."),
                )
            })
            .await??;
        debug!("got the result");
        match result {
            FunctionResult::Value(v) => {
                Ok(serde_json::ser::to_string(&serde_json::Value::from(v))?)
            }
            FunctionResult::ConvexError(e) => Err(anyhow!("ConvexError in query: {e}").into()),
            FunctionResult::ErrorMessage(msg) => {
                Err(anyhow!("ErrorMessage for query: {msg}").into())
            }
        }
    }

    /// Subscribe to updates to a query against the Convex backend.
    ///
    /// The [QuerySubscriber] will be called back with initial query results and it will continue to
    /// get called as the underlying data changes.
    ///
    /// The returned [SubscriptionHandle] can be used to cancel the subscription.
    pub async fn subscribe(
        &self,
        name: String,
        args: HashMap<String, String>,
        subscriber: Arc<dyn QuerySubscriber>,
    ) -> Result<Arc<SubscriptionHandle>, ClientError> {
        let mut client = self.connected_client().await?;
        debug!("New subscription");
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
                        let new_val = new_val.expect("Client dropped prematurely");
                        match new_val {
                            FunctionResult::Value(value) => {
                                debug!("Updating with {value:?}");
                                subscriber.on_update(serde_json::ser::to_string(&serde_json::Value::from(value)).unwrap())},
                            FunctionResult::ErrorMessage(message) => subscriber.on_error(message, None),
                            FunctionResult::ConvexError(error) => subscriber.on_error(error.message, Some(serde_json::ser::to_string(&serde_json::Value::from(error.data)).unwrap()))
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
        let mut client = self.connected_client().await?;

        let result = self
            .rt
            .spawn(async move { client.mutation(&name, parse_json_args(args)).await })
            .await??;

        match result {
            FunctionResult::Value(v) => {
                Ok(serde_json::ser::to_string(&serde_json::Value::from(v))?)
            }
            FunctionResult::ConvexError(e) => Err(anyhow!("ConvexError in mutation: {e}").into()),
            FunctionResult::ErrorMessage(msg) => {
                Err(anyhow!("ErrorMessage for mutation: {msg}").into())
            }
        }
    }

    /// Run an action on the Convex backend.
    pub async fn action(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        let mut client = self.connected_client().await?;
        debug!("Running action: {}", name);
        let result = self
            .rt
            .spawn(async move { client.action(&name, parse_json_args(args)).await })
            .await??;

        debug!("Got action result: {:?}", result);
        match result {
            FunctionResult::Value(v) => {
                Ok(serde_json::ser::to_string(&serde_json::Value::from(v))?)
            }
            FunctionResult::ConvexError(e) => Err(anyhow!("ConvexError in action: {e}").into()),
            FunctionResult::ErrorMessage(msg) => {
                Err(anyhow!("ErrorMessage for action: {msg}").into())
            }
        }
    }

    /// Provide an OpenID Connect ID token to be associated with this client.
    ///
    /// Doing so will share that information with the Convex backend and a valid token will give the
    /// backend knowledge of a logged in user.
    ///
    /// Passing [None] for the token will disassociate a previous token, effectively returning to a
    /// logged out state.
    pub async fn set_auth(&self, token: Option<String>) -> Result<(), ClientError> {
        let mut client = self.connected_client().await?;
        self.rt
            .spawn(async move { client.set_auth(token).await })
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
uniffi::include_scaffolding!("convex-mobile");
