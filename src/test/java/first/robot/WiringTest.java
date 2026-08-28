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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;
import org.wpilib.util.AlertDataJNI;
import org.wpilib.util.AlertDataJNI.AlertInfo;

// REVLib's libREVLibWpi.so needs fmt::v12::vformat, and no WPILib this project can compile against
// exports it, so constructing a SPARK terminates the JVM with exit 127. Robot's constructor builds
// eight of them, and the process dies before the first assertion — taking the rest of the suite in
// the same executor with it. Delete this annotation when a SPARK can be constructed.
@Disabled("constructing a SPARK terminates the JVM")
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
