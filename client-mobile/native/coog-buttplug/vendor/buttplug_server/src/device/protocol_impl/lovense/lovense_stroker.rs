// Buttplug Rust Source Code File - See https://buttplug.io for more info.
//
// Copyright 2016-2026 Nonpolynomial Labs LLC. All rights reserved.
//
// Licensed under the BSD 3-Clause license. See LICENSE file in the project root
// for full license information.
//
// Coog patch: Solace Pro keep-moving after stop.
// - Gate FSetSite seeker with `halted` on Oscillate:0
// - Send Mply:0:0 (not Mply:0:20) on stop
// - Do NOT pin FSetSite on stop — FSetSite:0 on connect drove the sleeve home

use crate::device::{
  hardware::{Hardware, HardwareCommand, HardwareWriteCmd},
  protocol::{ProtocolHandler, ProtocolKeepaliveStrategy},
};
use buttplug_core::{errors::ButtplugDeviceError, message::InputReadingV4, util::async_manager};
use buttplug_server_device_config::Endpoint;
use futures::future::BoxFuture;
use std::{
  sync::{
    Arc,
    atomic::{AtomicBool, AtomicU32, Ordering},
  },
  time::Duration,
};
use uuid::{Uuid, uuid};

const LOVENSE_STROKER_PROTOCOL_UUID: Uuid = uuid!("a97fc354-5561-459a-bc62-110d7c2868ac");

struct LovenseStrokerState {
  goal_position: AtomicU32,
  current_position: AtomicU32,
  duration: AtomicU32,
  /// When true, the FSetSite seeker must not write — set by Oscillate:0 / stop.
  halted: AtomicBool,
}

impl LovenseStrokerState {
  fn new() -> Self {
    Self {
      goal_position: AtomicU32::new(0),
      current_position: AtomicU32::new(0),
      duration: AtomicU32::new(0),
      halted: AtomicBool::new(true),
    }
  }
}

pub struct LovenseStroker {
  state: Arc<LovenseStrokerState>,
}

impl LovenseStroker {
  pub fn new(hardware: Arc<Hardware>, _need_range_zerod: bool) -> Self {
    let state = Arc::new(LovenseStrokerState::new());
    buttplug_core::spawn!(
      "LovenseStroker update linear movement",
      update_linear_movement(hardware.clone(), state.clone(),)
    );
    Self { state }
  }
}

impl ProtocolHandler for LovenseStroker {
  fn keepalive_strategy(&self) -> ProtocolKeepaliveStrategy {
    super::keepalive_strategy()
  }

  fn handle_hw_position_with_duration_cmd(
    &self,
    _feature_index: u32,
    _feature_id: Uuid,
    position: u32,
    duration: u32,
  ) -> Result<Vec<HardwareCommand>, ButtplugDeviceError> {
    self.state.duration.store(duration, Ordering::Relaxed);
    self.state.goal_position.store(position, Ordering::Relaxed);
    self.state.halted.store(false, Ordering::Relaxed);
    Ok(vec![])
  }

  fn handle_battery_level_cmd(
    &self,
    device_index: u32,
    device: Arc<Hardware>,
    feature_index: u32,
    feature_id: Uuid,
  ) -> BoxFuture<'static, Result<InputReadingV4, ButtplugDeviceError>> {
    super::handle_battery_level_cmd(device_index, device, feature_index, feature_id)
  }

  fn handle_output_oscillate_cmd(
    &self,
    _feature_index: u32,
    feature_id: Uuid,
    speed: u32,
  ) -> Result<Vec<HardwareCommand>, ButtplugDeviceError> {
    if speed == 0 {
      self.state.halted.store(true, Ordering::Relaxed);
      let current_position = self.state.current_position.load(Ordering::Relaxed);
      self.state.duration.store(0, Ordering::Relaxed);
      self
        .state
        .goal_position
        .store(current_position, Ordering::Relaxed);
      // Solace Pro can keep ~1Hz firmware thrusting with no further app commands
      // (stuck Mply / speed mode). Distinct command_ids so device-task dedupe
      // does not collapse this to a single write. No FSetSite — that restarts motion.
      const STOP_MPLY0: Uuid = uuid!("b1a10001-5561-459a-bc62-110d7c2868ac");
      const STOP_MPLY20: Uuid = uuid!("b1a10002-5561-459a-bc62-110d7c2868ac");
      const STOP_VIB0: Uuid = uuid!("b1a10003-5561-459a-bc62-110d7c2868ac");
      const STOP_LVS0: Uuid = uuid!("b1a10004-5561-459a-bc62-110d7c2868ac");
      let _ = feature_id;
      return Ok(vec![
        HardwareWriteCmd::new(&[STOP_MPLY0], Endpoint::Tx, b"Mply:0:0;".to_vec(), false).into(),
        HardwareWriteCmd::new(&[STOP_MPLY20], Endpoint::Tx, b"Mply:0:20;".to_vec(), false).into(),
        HardwareWriteCmd::new(&[STOP_VIB0], Endpoint::Tx, b"Vibrate:0;".to_vec(), false).into(),
        HardwareWriteCmd::new(&[STOP_LVS0], Endpoint::Tx, b"LVS:0:0:0;".to_vec(), false).into(),
      ]);
    }
    self.state.halted.store(false, Ordering::Relaxed);
    Ok(vec![HardwareWriteCmd::new(
      &[feature_id],
      Endpoint::Tx,
      format!("Mply:{}:20;", speed).as_bytes().to_vec(),
      false,
    )
    .into()])
  }
}

async fn update_linear_movement(device: Arc<Hardware>, state: Arc<LovenseStrokerState>) {
  let mut last_goal_position = 0i32;
  let mut current_move_amount = 0i32;
  let mut current_position = 0i32;
  loop {
    if state.halted.load(Ordering::Relaxed) {
      current_position = state.current_position.load(Ordering::Relaxed) as i32;
      last_goal_position = state.goal_position.load(Ordering::Relaxed) as i32;
      async_manager::sleep(Duration::from_millis(100)).await;
      continue;
    }

    let goal_position = state.goal_position.load(Ordering::Relaxed) as i32;
    if last_goal_position != goal_position {
      last_goal_position = goal_position;
      let move_steps = (state.duration.load(Ordering::Relaxed) / 100).max(1);
      let distance = goal_position - current_position;
      current_move_amount = distance / move_steps as i32;
      if current_move_amount == 0 {
        current_move_amount = distance.signum();
      }
    }

    if current_position == last_goal_position {
      async_manager::sleep(Duration::from_millis(100)).await;
      continue;
    }

    current_position += current_move_amount;
    if current_move_amount < 0 {
      if current_position < last_goal_position {
        current_position = last_goal_position;
      }
    } else if current_position > last_goal_position {
      current_position = last_goal_position;
    }
    state
      .current_position
      .store(current_position as u32, Ordering::Relaxed);

    if state.halted.load(Ordering::Relaxed) {
      async_manager::sleep(Duration::from_millis(100)).await;
      continue;
    }

    let lovense_cmd = format!("FSetSite:{current_position};");
    let hardware_cmd: HardwareWriteCmd = HardwareWriteCmd::new(
      &[LOVENSE_STROKER_PROTOCOL_UUID],
      Endpoint::Tx,
      lovense_cmd.into_bytes(),
      false,
    );
    if device.write_value(&hardware_cmd).await.is_err() {
      return;
    }
    async_manager::sleep(Duration::from_millis(100)).await;
  }
}
