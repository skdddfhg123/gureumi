// Pleos Connect Virtual Console (http://127.0.0.1:8088) 페이지의 DevTools/브라우저 패널 JS 콘솔에서 실행합니다.
// 약 12초짜리 시연 트립: D → 가속 → 정속(에피소드 arm) → 부드러운 정지(브레이크 9%) → 재가속 → 급제동(60%) → P.
// 숨겨진 브라우저 패널에서는 requestAnimationFrame이 멈추고 타이머도 1초 간격으로 느려지므로,
// physicsTick을 복제하되 지난 실제 시간만큼 0.05초 단위로 나눠 적분합니다(틱 빈도와 무관하게 시뮬레이션 시간이 맞음).
// 에뮬레이터는 주행 중 종료 전력이 있어 한 트립을 15초 안에 끝냅니다(reference/spike.md §4).
//
// 판정 규칙과 맞춘 수치(reference/rules.md): 부드러운 정지는 "정속(감속 ≤0.5 m/s²)에서 arm → 최대 감속 ≤2.5 m/s²로 정지".
//   - 정속 구간: 페달 물리를 끄고 accelerator_pct를 드래그와 맞춰(약 25 kph에서 1.6%) 1.5초 유지
//   - 9% 제동 = 3.8 kph/s(1.05 m/s²) + 관성 감속 ≈ 1.6 m/s² → SmoothStop
//   - 60% 제동 = 25 kph/s(7 m/s²) → HardBrake
(() => {
  const gear = (title) => {
    const b = [...document.querySelectorAll('#gearButtons button')].find((x) => x.title === title);
    if (!b) throw new Error('gear button not found: ' + title);
    b.onclick();
  };
  const step = (dt) => {
    const accelTarget = pedals.accelerator && isDriveGear() ? 100 : 0;
    const brakeTarget = pedals.brake ? 100 : 0;
    if (pedalControl.accelerator) vehicle.accelerator_pct = approach(vehicle.accelerator_pct, accelTarget, (accelTarget > vehicle.accelerator_pct ? 170 : 230) * dt);
    if (pedalControl.brake) vehicle.brake_pct = approach(vehicle.brake_pct, brakeTarget, (brakeTarget > vehicle.brake_pct ? 220 : 260) * dt);
    if (pedalControl.accelerator && !pedals.accelerator && vehicle.accelerator_pct === 0) pedalControl.accelerator = false;
    if (pedalControl.brake && !pedals.brake && vehicle.brake_pct === 0) pedalControl.brake = false;
    const throttle = vehicle.accelerator_pct / 100;
    const bk = vehicle.brake_pct / 100;
    const driveFactor = currentGear === 'Reverse' ? 0.45 : 1.0;
    const driveAccel = isDriveGear() ? throttle * 19.0 * driveFactor : 0;
    const brakeDecel = bk * 42.0;
    const coastDecel = vehicle.speed_kph > 0 ? 1.2 + vehicle.speed_kph * 0.035 : 0;
    const parkDrag = currentGear === 'Park' ? 18.0 : 0;
    const delta = driveAccel - brakeDecel - (throttle > 0 ? vehicle.speed_kph * 0.012 : coastDecel) - parkDrag;
    vehicle.speed_kph = clamp(vehicle.speed_kph + delta * dt, 0, 240);
    if (vehicle.speed_kph < 0.35 && throttle === 0) vehicle.speed_kph = 0;
  };
  const tick = () => {
    const now = performance.now();
    let remaining = Math.min((now - lastFrame) / 1000, 1.5);
    lastFrame = now;
    while (remaining > 0) { const h = Math.min(0.05, remaining); step(h); remaining -= h; }
    renderAll();
    if (pedalControl.accelerator || pedalControl.brake || vehicle.speed_kph > 0) { markLocalControl(); queueCommit(now); }
  };
  const at = (ms, fn) => setTimeout(fn, ms);
  const log = [];
  const t0 = performance.now();
  const mark = (m) => log.push(((performance.now() - t0) / 1000).toFixed(1) + 's ' + m + ' v=' + vehicle.speed_kph.toFixed(0));
  lastFrame = t0;
  const timer = setInterval(tick, 50);
  window.__trip = { log, stop: () => clearInterval(timer) };

  gear('Drive'); mark('D');
  at(300, () => { pedals.accelerator = true; pedalControl.accelerator = true; mark('accel on'); });
  // 정속: 페달 물리를 끄고 드래그만큼만 스로틀 유지 → 감속 ≈ 0 → 에피소드 arm
  at(1800, () => { pedals.accelerator = false; pedalControl.accelerator = false; vehicle.accelerator_pct = 1.6; mark('steady'); });
  // 부드러운 정지: 브레이크 9%를 정지까지 유지
  at(3300, () => { vehicle.accelerator_pct = 0; pedalControl.brake = false; vehicle.brake_pct = 9; mark('soft brake 9%'); });
  at(8300, () => { vehicle.brake_pct = 0; pedals.accelerator = true; pedalControl.accelerator = true; mark('accel again'); });
  at(10100, () => { pedals.accelerator = false; pedalControl.brake = false; vehicle.brake_pct = 60; mark('HARD brake 60%'); });
  at(11800, () => { vehicle.brake_pct = 0; gear('Park'); mark('P'); });
  at(13300, () => { clearInterval(timer); mark('end'); });
  return 'trip scheduled; read window.__trip.log after 14 s';
})();
