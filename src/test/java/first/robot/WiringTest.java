// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Seconds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;
import org.wpilib.util.AlertDataJNI;
import org.wpilib.util.AlertDataJNI.AlertInfo;

// This is the check that says whether src/main/native/revshim is still needed: run it with no
// LD_PRELOAD set, and a green run means REVLib's native binds on its own and the shim can go.
@ResourceLock("timing")
class WiringTest {
  private static final String OPMODE = "DrivePathCheck";

  // Short of the first leg, deliberately: the script drives a closed square, so a robot stepped
  // through the whole of it ends where it started and displaces nothing.
  private static final Time SCRIPT_SLICE = Seconds.of(1);

  private static final Time SHUTDOWN = Seconds.of(5);

  // Loose on purpose. Tier 1 owns every number in this project, and an assertion tight enough to
  // say anything about the physics here would go red every time the physics changed.
  private static final Distance MOVED = Meters.of(0.1);

  @BeforeEach
  void setUp() {
    // Before any SimHooks call: the timing hooks lock a mutex the HAL creates, so a JVM that has
    // not initialised it segfaults here rather than throwing. Whether some earlier test already
    // did is not this test's business to know.
    HAL.initialize();
    SimHooks.pauseTiming();
    SimHooks.setProgramStarted(false);
    DriverStationSim.resetData();
  }

  @AfterEach
  void tearDown() {
    SimHooks.resumeTiming();
  }

  @Test
  void theScriptedCheckDrivesTheRealRobot() throws InterruptedException {
    // getAlerts() is process-wide and nothing clears it, so the alerts other classes raised on
    // purpose are the starting state rather than a failure of this one.
    var before = highAlerts();
    var failure = new AtomicReference<Throwable>();
    var robot = new Robot();
    var thread = new Thread(robot::startCompetition);
    thread.setUncaughtExceptionHandler((t, e) -> failure.set(e));

    double displacement;
    List<String> raised;
    try {
      thread.start();
      SimHooks.waitForProgramStart();

      // Attached with no alliance is itself a HIGH alert, so the alliance is part of standing the
      // robot up rather than part of the scenario.
      DriverStationSim.setDsAttached(true);
      DriverStationSim.setAllianceStationId(AllianceStationID.BLUE_1);
      DriverStationSim.notifyNewData();

      var option = utilityOpMode();

      // Selected first and enabled second, which is the order the operator does it in: the opmode
      // is constructed on selection, and its enabled-trigger needs an edge to fire on.
      //
      // The mode and the id are separate fields on the simulated Driver Station, and the control
      // word is assembled from both. Setting only the id leaves the mode bits clear, so the id the
      // robot reads back is not the id the opmode was registered under, and the lookup misses
      // without raising anything a test can see.
      DriverStationSim.setRobotMode(option.getMode());
      DriverStationSim.setOpMode(option.id);
      DriverStationSim.notifyNewData();
      SimHooks.stepTiming(Constants.LOOP_PERIOD.in(Seconds));

      DriverStationSim.setEnabled(true);
      DriverStationSim.notifyNewData();
      SimHooks.stepTiming(SCRIPT_SLICE.in(Seconds));

      // Read before the teardown below, which closes the alerts it would otherwise be asked about.
      displacement = robot.poseEstimator.getEstimatedPose().getTranslation().getNorm();
      raised = new ArrayList<>(highAlerts());
      raised.removeAll(before);
    } finally {
      robot.endCompetition();
      thread.join((long) SHUTDOWN.in(Milliseconds));
      robot.clearOpModes();
      robot.close();
    }

    assertFalse(thread.isAlive(), "the robot loop did not exit");
    // Before the displacement, which a robot whose thread died also fails, and less usefully.
    assertNull(failure.get());
    assertEquals(List.of(), raised);
    assertTrue(
        displacement > MOVED.in(Meters),
        "expected displacement > " + MOVED.in(Meters) + ", was " + displacement);
  }

  private static OpModeOption utilityOpMode() {
    var options = DriverStationSim.getOpModeOptions();
    var option = Arrays.stream(options).filter(o -> OPMODE.equals(o.name)).findFirst().orElse(null);

    assertNotNull(option, "no opmode named " + OPMODE + " in " + names(options));
    assertEquals(RobotMode.UTILITY, option.getMode());
    return option;
  }

  private static List<String> names(OpModeOption[] options) {
    return Arrays.stream(options).map(o -> o.name).toList();
  }

  private static List<String> highAlerts() {
    return Arrays.stream(AlertDataJNI.getAlerts())
        .filter(a -> a.activeStartTime != 0 && a.level == AlertDataJNI.LEVEL_HIGH)
        .map(WiringTest::id)
        .toList();
  }

  private static String id(AlertInfo alert) {
    return alert.group + "/" + alert.id;
  }
}
