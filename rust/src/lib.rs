use android_logger::Config;
use anyhow::anyhow;
use convex::{ConvexClient, FunctionResult, Value};
use futures::channel::oneshot::{self, Sender};
use futures::{pin_mut, select_biased, FutureExt, StreamExt};
use log::debug;
use log::LevelFilter;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;

use std::collections::BTreeMap;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::{sync::RwLock, task::JoinError};

#[derive(Debug, thiserror::Error)]
#[error("{e:?}")]
struct ClientError {
    e: anyhow::Error,
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

#[derive(Clone)]
enum ConvexValueType {
    Null,
    Int64,
    Float64,
    Boolean,
    String,
    Bytes,
    Array,
    Object,
}

impl Default for ConvexValueType {
    fn default() -> Self {
        ConvexValueType::Null
    }
}

#[derive(Default)]
pub struct ConvexValue {
    vtype: ConvexValueType,
    int64_value: Option<i64>,
    float64_value: Option<f64>,
    bool_value: Option<bool>,
    string_value: Option<String>,
    bytes_value: Option<Vec<u8>>,
    array_value: Option<Vec<ConvexValue>>,
    object_value: Option<HashMap<String, ConvexValue>>,
}

pub trait QuerySubscriber: Send + Sync {
    fn on_update(&self, value: ConvexValue) -> ();

    fn on_error(&self, message: String, value: Option<ConvexValue>) -> ();
}

pub struct SubscriptionHandle {
    cancel_sender: Mutex<Option<Sender<()>>>
}

impl SubscriptionHandle {
    pub fn new(cancel_sender: Sender<()>) -> Self {
        SubscriptionHandle{cancel_sender: Mutex::new(Some(cancel_sender))}
    }

    pub fn cancel(&self) {
            if let Some(sender) = self.cancel_sender.lock().take() {
                sender.send(()).unwrap();
            }
    }
}

struct MobileConvexClient {
    deployment_url: String,
    client: OnceCell<RwLock<ConvexClient>>,
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

    pub async fn connect(&self) -> Result<(), ClientError> {
        let url = self.deployment_url.clone();
        let client = self
            .rt
            .spawn(async move { ConvexClient::new(url.as_str()).await })
            .await?;
        match client {
            Ok(client) => {
                self.client.get_or_init(|| RwLock::new(client));
                Ok(())
            }
            Err(_) => Err(anyhow!("blah").into()),
        }
    }

    pub async fn query(&self, name: String) -> Result<ConvexValue, ClientError> {
        let mut client = match self.client.get() {
            Some(c) => Ok(c),
            None => Err(anyhow!("must connect client first")),
        }?
        .write()
        .await
        .clone();
        debug!("got the client");
        let result = self
            .rt
            .spawn(async move {
                Ok::<FunctionResult, ClientError>(
                    client
                        .subscribe(name.as_str(), BTreeMap::new())
                        .await?
                        .next()
                        .await
                        .expect("INTERNAL BUG: Convex Client dropped prematurely."),
                )
            })
            .await??;
        debug!("got the result");
        match result {
            FunctionResult::Value(v) => Ok(value_to_convex_value(v)),
            _ => Err(anyhow!("error querying").into()),
        }
    }

    pub async fn subscribe(
        &self,
        name: String,
        subscriber: Arc<dyn QuerySubscriber>,
    ) -> Result<Option<Arc<SubscriptionHandle>>, ClientError> {
        let mut client = match self.client.get() {
            Some(c) => Ok(c),
            None => Err(anyhow!("must connect client first")),
        }?
        .write()
        .await
        .clone();
        debug!("New subscription");
        let mut subscription = client.subscribe(name.as_str(), BTreeMap::new()).await?;
        let (cancel_sender, cancel_receiver) = oneshot::channel::<()>();
        self.rt.spawn(async move {
            let cancel_fut = cancel_receiver.fuse();
            pin_mut!(cancel_fut);
            loop {
                select_biased! {
                    new_val = subscription.next().fuse() => {
                        let new_val = new_val.expect("Client dropped prematurely");
                        match new_val {
                            FunctionResult::Value(value) => subscriber.on_update(value_to_convex_value(value)),
                            FunctionResult::ErrorMessage(message) => subscriber.on_error(message, None),
                            FunctionResult::ConvexError(error) => subscriber.on_error(error.message, Some(value_to_convex_value(error.data)))
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
        args: HashMap<String, ConvexValue>,
    ) -> Result<ConvexValue, ClientError> {
        let mut client = match self.client.get() {
            Some(c) => Ok(c),
            None => Err(anyhow!("must connect client first")),
        }?
        .write()
        .await
        .clone();

        let result = self
            .rt
            .spawn(async move {
                client
                    .mutation(
                        &name,
                        args.into_iter()
                            .map(|(k, v)| (k, convex_value_to_value(v)))
                            .collect(),
                    )
                    .await
            })
            .await??;

        match result {
            FunctionResult::Value(v) => Ok(value_to_convex_value(v)),
            _ => Err(anyhow!("error mutating").into()),
        }
    }
}

fn value_to_convex_value(value: Value) -> ConvexValue {
    match value {
        Value::Null => ConvexValue {
            ..Default::default()
        },
        Value::Int64(v) => ConvexValue {
            vtype: ConvexValueType::Int64,
            int64_value: Some(v),
            ..Default::default()
        },
        Value::Float64(v) => ConvexValue {
            vtype: ConvexValueType::Float64,
            float64_value: Some(v),
            ..Default::default()
        },
        Value::Array(v) => ConvexValue {
            vtype: ConvexValueType::Array,
            array_value: Some(v.into_iter().map(value_to_convex_value).collect()),
            ..Default::default()
        },
        Value::String(v) => ConvexValue {
            vtype: ConvexValueType::String,
            string_value: Some(v),
            ..Default::default()
        },
        Value::Object(v) => ConvexValue {
            vtype: ConvexValueType::Object,
            object_value: Some(
                v.into_iter()
                    .map(|(k, v)| (k.to_owned(), value_to_convex_value(v)))
                    .collect(),
            ),
            ..Default::default()
        },
        _ => panic!("unimplemented"),
    }
}

fn convex_value_to_value(value: ConvexValue) -> Value {
    match value {
        ConvexValue {
            vtype: ConvexValueType::Null,
            ..
        } => Value::Null,
        ConvexValue {
            vtype: ConvexValueType::Int64,
            int64_value: Some(value),
            ..
        } => Value::Int64(value),
        ConvexValue {
            vtype: ConvexValueType::Float64,
            float64_value: Some(value),
            ..
        } => Value::Float64(value),
        ConvexValue {
            vtype: ConvexValueType::String,
            string_value: Some(value),
            ..
        } => Value::String(value),
        ConvexValue {
            vtype: ConvexValueType::Boolean,
            bool_value: Some(value),
            ..
        } => Value::Boolean(value),
        ConvexValue {
            vtype: ConvexValueType::Bytes,
            bytes_value: Some(value),
            ..
        } => Value::Bytes(value),
        ConvexValue {
            vtype: ConvexValueType::Array,
            array_value: Some(value),
            ..
        } => Value::Array(
            value
                .into_iter()
                .map(|v| convex_value_to_value(v))
                .collect(),
        ),
        ConvexValue {
            vtype: ConvexValueType::Object,
            object_value: Some(value),
            ..
        } => Value::Object(
            value
                .into_iter()
                .map(|(k, v)| (k.to_owned(), convex_value_to_value(v)))
                .collect(),
        ),
        _ => panic!("unmatched value converting ConvexValue -> Value"),
    }
}

uniffi::include_scaffolding!("convex-mobile");
