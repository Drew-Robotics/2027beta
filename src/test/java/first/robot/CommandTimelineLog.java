// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Seconds;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;
import org.wpilib.units.measure.Time;

// Not a gate — a tool, like sysidLog. It drives the real robot through a scripted match so the
// command timeline has something in it to look at, and leaves the log where AdvantageScope opens.
@ResourceLock("timing")
class CommandTimelineLog {
  private static final Time SHUTDOWN = Seconds.of(5);

  @BeforeEach
  void setUp() {
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
  void writeATimeline() throws InterruptedException {
    var robot = new Robot();
    var thread = new Thread(robot::startCompetition);

    try {
      thread.start();
      SimHooks.waitForProgramStart();

      DriverStationSim.setDsAttached(true);
      DriverStationSim.setAllianceStationId(AllianceStationID.BLUE_1);
      DriverStationSim.setJoystickIsGamepad(Constants.DRIVER_PORT, true);
      DriverStationSim.setJoystickAxesAvailable(Constants.DRIVER_PORT, 0x3f);
      DriverStationSim.notifyNewData();

      // Disabled, with Robot's own default command holding the drive. One Scheduled, then nothing.
      step(Seconds.of(1.5));

      // Autonomous. SweepLeftAuto forks Auto.TimeElapsed, awaits Drive.FollowPath, and fires the
      // Auto.MarkZoneEntry one-shot off a pose trigger — the one event the proto snapshot is blind
      // to, and the whole reason /Commands/Events exists.
      select("SweepLeftAuto");
      enable(Seconds.of(6));

      // Disable. Everything the opmode left running is cancelled here.
      disable(Seconds.of(1.5));

      // Teleop, with the sticks off centre so Drive.DriverControl is actually driving.
      select("DefaultTeleop");
      stick(-0.6, 0.25, 0.0);
      enable(Seconds.of(2));
      stick(-0.2, -0.5, 0.45);
      step(Seconds.of(2));
      stick(0.0, 0.0, 0.0);
      step(Seconds.of(1));

      disable(Seconds.of(1.5));
    } finally {
      robot.endCompetition();
      thread.join((long) SHUTDOWN.in(Milliseconds));
      robot.clearOpModes();
      robot.close();
    }
  }

  private static void stick(double leftY, double leftX, double rightX) {
    DriverStationSim.setJoystickAxis(Constants.DRIVER_PORT, 1, leftY);
    DriverStationSim.setJoystickAxis(Constants.DRIVER_PORT, 0, leftX);
    DriverStationSim.setJoystickAxis(Constants.DRIVER_PORT, 4, rightX);
    DriverStationSim.notifyNewData();
  }

  // Selected before it is enabled, which is the order the operator does it in: the opmode is
  // constructed on selection, and its enabled-trigger needs an edge to fire on.
  private static void select(String name) {
    var options = DriverStationSim.getOpModeOptions();
    var option = Arrays.stream(options).filter(o -> name.equals(o.name)).findFirst().orElse(null);
    assertNotNull(option, "no opmode named " + name + " in " + names(options));

    DriverStationSim.setRobotMode(option.getMode());
    DriverStationSim.setOpMode(option.id);
    DriverStationSim.notifyNewData();
    step(Constants.LOOP_PERIOD);
  }

  private static void enable(Time hold) {
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    step(hold);
  }

  private static void disable(Time hold) {
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    step(hold);
  }

  private static void step(Time slice) {
    SimHooks.stepTiming(slice.in(Seconds));
  }

  private static java.util.List<String> names(OpModeOption[] options) {
    return Arrays.stream(options).map(o -> o.name).toList();
  }
}
