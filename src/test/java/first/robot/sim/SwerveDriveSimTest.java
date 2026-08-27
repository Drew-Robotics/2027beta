// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Meters;
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
      for (int i = 0; i < MODULES; i++) {
        // Rotation2d reads back over [-0.5, 0.5) and the analog sensor over [0, 1).
        double azimuth = MathUtil.inputModulus(state[i].azimuth().getRotations(), 0, 1);
        steerVolts[i] = steerLoops[i].calculate(azimuth, SUB_STEP);
      }
      state = sim.update(driveVolts, steerVolts, SUB_STEP);
    }
  }
}
