//! In-process Intiface engine for Coog Android.
//!
//! Exposes JNI:
//! - `CoogButtplug.start(port)` → run engine on `127.0.0.1:port` with BLE
//! - `CoogButtplug.stop()` → cancel engine and wait for BLE teardown
//!
//! The existing Kotlin [ButtplugClient] keeps talking over WebSocket to that port.

use intiface_engine::{EngineOptionsBuilder, IntifaceEngine};
use log::{error, info};
use once_cell::sync::OnceCell;
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

struct EngineState {
    runtime: Runtime,
    stop: CancellationToken,
    /// Signalled when engine.run() returns (BLE teardown finished).
    done_rx: mpsc::Receiver<()>,
}

static STATE: OnceCell<Mutex<Option<EngineState>>> = OnceCell::new();

fn state() -> &'static Mutex<Option<EngineState>> {
    STATE.get_or_init(|| Mutex::new(None))
}

fn start_engine(port: u16) -> Result<(), String> {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("CoogButtplugNative"),
    );

    let mut guard = state().lock().map_err(|e| e.to_string())?;
    if guard.is_some() {
        return Ok(()); // already running
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("coog-intiface")
        .build()
        .map_err(|e| e.to_string())?;

    let stop = CancellationToken::new();
    let stop_child = stop.clone();
    let listen = format!("127.0.0.1:{port}");
    let (done_tx, done_rx) = mpsc::channel::<()>();

    // Await engine.run() until stop so BLE peripherals disconnect cleanly.
    runtime.spawn(async move {
        let engine = Arc::new(IntifaceEngine::default());
        let eng_stop = engine.clone();
        let watcher = tokio::spawn(async move {
            stop_child.cancelled().await;
            info!("embedded Intiface stop requested");
            eng_stop.stop();
        });
        info!("starting embedded Intiface on {listen}");
        let mut builder = EngineOptionsBuilder::default();
        builder
            .server_name("Coog Embedded")
            .use_bluetooth_le(true)
            .websocket_listen_address(&listen)
            .max_ping_time(0);
        let options = builder.finish();
        match engine.run(&options, None, &None).await {
            Ok(()) => info!("embedded Intiface exited cleanly"),
            Err(e) => error!("intiface engine exited: {e:?}"),
        }
        watcher.abort();
        let _ = done_tx.send(());
    });

    *guard = Some(EngineState {
        runtime,
        stop,
        done_rx,
    });
    Ok(())
}

fn stop_engine() {
    if let Ok(mut guard) = state().lock() {
        if let Some(st) = guard.take() {
            st.stop.cancel();
            // Wait for engine.run() to finish disconnecting BLE before killing the runtime.
            match st.done_rx.recv_timeout(Duration::from_secs(4)) {
                Ok(()) => info!("embedded Intiface run finished after stop"),
                Err(_) => error!("embedded Intiface stop timed out waiting for run exit"),
            }
            st.runtime.shutdown_timeout(Duration::from_secs(2));
            info!("embedded Intiface runtime shut down");
        }
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::objects::JClass;
    use jni::sys::{jboolean, jint, JNI_VERSION_1_6};
    use jni::JavaVM;

    #[unsafe(no_mangle)]
    pub unsafe extern "system" fn JNI_OnLoad(
        vm: *mut jni::sys::JavaVM,
        _res: *mut std::ffi::c_void,
    ) -> jint {
        // Safety: JVM passes a valid JavaVM pointer to JNI_OnLoad.
        let vm = unsafe { JavaVM::from_raw(vm) };
        let _ = vm.attach_current_thread(|env| {
            btleplug::platform::init(env).map_err(|_| jni::errors::Error::JavaException)
        });
        JNI_VERSION_1_6
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tv_coog_app_interactive_EmbeddedIntiface_nativeStart(
        _env: jni::EnvUnowned,
        _class: JClass,
        port: jint,
    ) -> jboolean {
        let port = if port <= 0 { 12345 } else { port as u16 };
        match start_engine(port) {
            Ok(()) => true,
            Err(e) => {
                error!("nativeStart failed: {e}");
                false
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tv_coog_app_interactive_EmbeddedIntiface_nativeStop(
        _env: jni::EnvUnowned,
        _class: JClass,
    ) {
        stop_engine();
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tv_coog_app_interactive_EmbeddedIntiface_nativeAvailable(
        _env: jni::EnvUnowned,
        _class: JClass,
    ) -> jboolean {
        true
    }
}
