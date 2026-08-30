// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Rotations;
import static org.wpilib.units.Units.Seconds;
import static org.wpilib.units.Units.Volts;

import first.robot.Constants;
import first.robot.DriveConstants;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.system.DCMotor;
import org.wpilib.math.util.MathUtil;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.Time;

class SwerveDriveSimTest {
  private static final int MODULES = 4;
  private static final double SUB_STEP = DriveConstants.CONTROLLER_PERIOD.in(Seconds);
  private static final int SUB_STEPS =
      (int) Math.round(Constants.LOOP_PERIOD.in(Seconds) / SUB_STEP);

  private static final double DRIVE_VOLTS = 6.0;
  private static final Time SETTLE = Seconds.of(2);
  private static final Time SPIN = Seconds.of(2);

  private final SwerveSimConfig config = DriveConstants.simConfig();
  private final SwerveDriveSim sim = new SwerveDriveSim(config);
  private final double[] driveVolts = new double[MODULES];
  private final double[] steerVolts = new double[MODULES];
  private final OnboardLoopSim[] steerLoops = new OnboardLoopSim[MODULES];

  private SimModuleState[] state;

  @BeforeEach
  void buildSteerLoops() {
    var gains = DriveConstants.SIM_GAINS.steer();
    for (int i = 0; i < MODULES; i++) {
      // The wrap range is the converted analog sensor's, which is not Rotation2d's.
      steerLoops[i] = OnboardLoopSim.position(gains.kP(), gains.kD(), gains.dFilter(), 0, 1);
    }
    state = sim.moduleStates();
  }

  @Test
  void aConstantVoltageSettlesAtTheFreeSpeedThatVoltageBuys() {
    Arrays.fill(driveVolts, DRIVE_VOLTS);

    advance(Seconds.of(3));

    // Terminal is where the motor draws no current, so the applied volts are all back-EMF: the
    // rotor turns at V times Kv and the wheel at that over the reduction.
    double expected = DRIVE_VOLTS * DCMotor.getNeoVortex(1).Kv / DriveConstants.DRIVE_REDUCTION;
    assertEquals(
        expected,
        state[0].wheelVelocityRadPerSec(),
        expected * 0.01,
        "the wheel did not reach the free speed " + DRIVE_VOLTS + " volts buys");
    assertEquals(
        expected * config.wheelRadius().in(Meters),
        sim.trueVelocity().vx,
        expected * config.wheelRadius().in(Meters) * 0.01,
        "the chassis is not moving at the speed its wheels are turning");
  }

  @Test
  void pureRotationTurnsTheRobotAndDoesNotMoveIt() {
    var kinematics = new SwerveDriveKinematics(config.moduleLocations());
    var targets = kinematics.toSwerveModuleVelocities(new ChassisVelocities(0, 0, 1));
    for (int i = 0; i < MODULES; i++) {
      steerLoops[i].setSetpoint(MathUtil.inputModulus(targets[i].angle.getRotations(), 0, 1));
    }

    advance(SETTLE);
    Arrays.fill(driveVolts, DRIVE_VOLTS);
    advance(SPIN);

    assertTrue(
        Math.abs(sim.trueVelocity().omega) > 5, "the robot is not turning: " + sim.trueVelocity());
    assertEquals(
        0,
        sim.truePose().getTranslation().getNorm(),
        0.01,
        "the robot translated while spinning: " + sim.truePose());
  }

  // The controller reports what it applied, and under a current limit that is not what it was
  // asked for: the two only meet once the wheel is turning fast enough that the back-EMF carries
  // most of the command.
  @Test
  void theAppliedVoltsAreTheLimitedOnesUntilTheWheelIsUpToSpeed() {
    double limited = config.drive().currentLimit().in(Amps) * config.drive().motor().R;
    Arrays.fill(driveVolts, 12.0);

    state = sim.update(driveVolts, steerVolts, SUB_STEP);

    assertEquals(
        limited,
        state[0].driveAppliedVolts(),
        limited * 0.02,
        "a step from rest was applied as more than the current limit allows");

    advance(Seconds.of(3));

    assertTrue(
        state[0].driveAppliedVolts() > 11.0,
        "the wheel is at speed and the command is still being limited: "
            + state[0].driveAppliedVolts());
  }

  @Test
  void theBatterySagsUnderTheCurrentItIsAskedFor() {
    Arrays.fill(driveVolts, 12.0);

    advance(Constants.LOOP_PERIOD);

    assertTrue(
        sim.batteryVoltage().lt(Volts.of(11)),
        "accelerating four modules from a stop did not sag the battery: " + sim.batteryVoltage());

    advance(Seconds.of(3));

    assertEquals(
        12.0,
        sim.batteryVoltage().in(Volts),
        0.5,
        "the battery did not recover once the wheels stopped accelerating");
  }

  // The whole of the floaty-simulation bug: the SPARK's feedback output is a duty cycle, so a
  // model that reads kP as volts steers at a twelfth of the authority the device has. A 90-degree
  // step took 895 ms on the log that found this; the module physically slews a turn in 274 ms.
  @Test
  void aQuarterTurnStepSettlesInsideTheTimeADriverWouldNotice() {
    for (int i = 0; i < MODULES; i++) {
      steerLoops[i].setSetpoint(0.25);
    }

    double settled = settleTime(0.25, Degrees.of(5), Seconds.of(1));

    assertTrue(
        settled < 0.3, "steer took " + Math.round(settled * 1000) + " ms to settle a quarter turn");
  }

  // Both SPARKs idle in brake, and zero volts into the plant is the short across the motor that
  // makes. A coasting module would hold its speed here instead.
  @Test
  void aWheelHandedZeroVoltsBrakesRatherThanCoasting() {
    Arrays.fill(driveVolts, DRIVE_VOLTS);
    advance(Seconds.of(3));
    double rolling = state[0].wheelVelocityRadPerSec();
    assertTrue(rolling > 1, "the wheel never spun up");

    Arrays.fill(driveVolts, 0);
    advance(Seconds.of(1));

    assertTrue(
        state[0].wheelVelocityRadPerSec() < rolling * 0.02,
        "a wheel handed zero volts kept rolling at "
            + state[0].wheelVelocityRadPerSec()
            + " of "
            + rolling);
  }

  // The other half of the floaty-simulation bug. The battery model was charged the winding
  // current, so four drives held at their limit down at a couple of volts read as 240 A and sagged
  // the rail to its floor -- at exactly the moment a launch needs the volts. A log measured 1440 ms
  // from rest to 90% of a request over 4 m/s.
  @Test
  void aFullThrottleLaunchReachesSpeedInTheTimeTheCurrentLimitAllows() {
    Arrays.fill(driveVolts, 12.0);

    double launched = timeToSpeed(4.0, Seconds.of(3));

    assertTrue(
        launched < 1.0, "the launch took " + Math.round(launched * 1000) + " ms to reach 4 m/s");
  }

  // A launch is current-limited, and a motor at its limit down at a couple of volts is a small
  // load on the pack. A rail that collapses here is charging the battery the winding's amps.
  @Test
  void aLaunchDoesNotCollapseTheRail() {
    Arrays.fill(driveVolts, 12.0);

    advance(Seconds.of(1));

    assertTrue(
        sim.batteryVoltage().gt(Volts.of(10)),
        "four current-limited modules sagged the pack to " + sim.batteryVoltage());
  }

  // Seconds until the chassis is at least this fast, or the timeout if it never is.
  private double timeToSpeed(double metresPerSecond, Time timeout) {
    int ticks = (int) Math.round(timeout.in(Seconds) / Constants.LOOP_PERIOD.in(Seconds));
    for (int tick = 0; tick < ticks; tick++) {
      tick();
      if (Math.hypot(sim.trueVelocity().vx, sim.trueVelocity().vy) >= metresPerSecond) {
        return (tick + 1) * Constants.LOOP_PERIOD.in(Seconds);
      }
    }
    return timeout.in(Seconds);
  }

  // Seconds until every module is inside tolerance of the setpoint, or the timeout if it never is.
  private double settleTime(double setpointRotations, Angle tolerance, Time timeout) {
    int ticks = (int) Math.round(timeout.in(Seconds) / Constants.LOOP_PERIOD.in(Seconds));
    for (int tick = 0; tick < ticks; tick++) {
      tick();
      boolean all = true;
      for (int i = 0; i < MODULES; i++) {
        double error =
            MathUtil.inputModulus(
                setpointRotations - MathUtil.inputModulus(state[i].azimuth().getRotations(), 0, 1),
                -0.5,
                0.5);
        all &= Math.abs(error) <= tolerance.in(Rotations);
      }
      if (all) {
        return (tick + 1) * Constants.LOOP_PERIOD.in(Seconds);
      }
    }
    return timeout.in(Seconds);
  }

  private void advance(Time duration) {
    int ticks = (int) Math.round(duration.in(Seconds) / Constants.LOOP_PERIOD.in(Seconds));
    for (int i = 0; i < ticks; i++) {
      tick();
    }
  }

  // One robot loop period, sub-stepped at the controller's own rate with the setpoints held
  // across the sub-steps: what a 200 Hz robot commanding a 1 kHz loop actually looks like.
  private void tick() {
    for (int step = 0; step < SUB_STEPS; step++) {
      double rail = sim.batteryVoltage().in(Volts);
      for (int i = 0; i < MODULES; i++) {
        // Rotation2d reads back over [-0.5, 0.5) and the analog sensor over [0, 1).
        double azimuth = MathUtil.inputModulus(state[i].azimuth().getRotations(), 0, 1);
        steerVolts[i] = steerLoops[i].calculate(azimuth, SUB_STEP, rail);
      }
      state = sim.update(driveVolts, steerVolts, SUB_STEP);
    }
  }
}
