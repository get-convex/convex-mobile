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

struct MobileConvexClient {
    deployment_url: String,
    client: OnceCell<ConvexClient>,
    rt: tokio::runtime::Runtime,
}

impl MobileConvexClient {
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

    async fn connected_client(&self) -> ConvexClient {
        let url = self.deployment_url.clone();

        self.client
            .get_or_init(async {
                let client = self
                    .rt
                    .spawn(async move { ConvexClient::new(url.as_str()).await })
                    .await
                    .unwrap();
                client.unwrap()
            })
            .await
            .clone()
    }

    pub async fn query(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        let mut client = self.connected_client().await;
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
            _ => Err(anyhow!("error querying").into()),
        }
    }

    pub async fn subscribe(
        &self,
        name: String,
        args: HashMap<String, String>,
        subscriber: Arc<dyn QuerySubscriber>,
    ) -> Result<Option<Arc<SubscriptionHandle>>, ClientError> {
        let mut client = self.connected_client().await;
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
        Ok(Some(Arc::new(SubscriptionHandle::new(cancel_sender))))
    }

    pub async fn mutation(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        let mut client = self.connected_client().await;

        let result = self
            .rt
            .spawn(async move { client.mutation(&name, parse_json_args(args)).await })
            .await??;

        match result {
            FunctionResult::Value(v) => {
                Ok(serde_json::ser::to_string(&serde_json::Value::from(v))?)
            }
            _ => Err(anyhow!("error mutating").into()),
        }
    }

    pub async fn action(
        &self,
        name: String,
        args: HashMap<String, String>,
    ) -> Result<String, ClientError> {
        let mut client = self.connected_client().await;
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
            _ => Err(anyhow!("error in action").into()),
        }
    }

    pub async fn set_auth(&self, token: Option<String>) {
        let mut client = self.connected_client().await;
        self.rt
            .spawn(async move { client.set_auth(token).await })
            .await
            .expect("Error joining thread");
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
