// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.DegreesPerSecond;
import static org.wpilib.units.Units.Inches;
import static org.wpilib.units.Units.KilogramSquareMeters;
import static org.wpilib.units.Units.Kilograms;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Pounds;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.RadiansPerSecondPerSecond;
import static org.wpilib.units.Units.Rotations;
import static org.wpilib.units.Units.Second;
import static org.wpilib.units.Units.Seconds;
import static org.wpilib.units.Units.Volts;

import first.robot.sim.SwerveSimConfig;
import org.wpilib.framework.RobotBase;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.numbers.N1;
import org.wpilib.math.numbers.N4;
import org.wpilib.math.system.DCMotor;
import org.wpilib.units.VoltageUnit;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.AngularAcceleration;
import org.wpilib.units.measure.AngularVelocity;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.LinearVelocity;
import org.wpilib.units.measure.Mass;
import org.wpilib.units.measure.MomentOfInertia;
import org.wpilib.units.measure.Time;
import org.wpilib.units.measure.Velocity;
import org.wpilib.units.measure.Voltage;

public final class DriveConstants {
  public static final int FRONT_LEFT_DRIVE_ID = 1;
  public static final int FRONT_LEFT_STEER_ID = 2;
  public static final int FRONT_RIGHT_DRIVE_ID = 3;
  public static final int FRONT_RIGHT_STEER_ID = 4;
  public static final int BACK_LEFT_DRIVE_ID = 5;
  public static final int BACK_LEFT_STEER_ID = 6;
  public static final int BACK_RIGHT_DRIVE_ID = 7;
  public static final int BACK_RIGHT_STEER_ID = 8;
  public static final int GYRO_ID = 9;

  // REV's documented SPARK control loop period, which is not the robot's.
  public static final Time CONTROLLER_PERIOD = Milliseconds.of(1);

  // Module angle and wheel travel are what odometry consumes, so they ride at the loop period.
  // Everything else is a diagnostic and rides slower. Raising one period raises its whole frame
  // group, because the setters keep the smaller of the requested and the already-set value.
  public static final Time ODOMETRY_FRAME_PERIOD = Constants.LOOP_PERIOD;
  public static final Time FAULT_FRAME_PERIOD = Milliseconds.of(50);
  public static final Time DIAGNOSTIC_FRAME_PERIOD = Milliseconds.of(100);

  // What the applied-output frame runs at while a characterisation is reading a column off it.
  public static final Time CHARACTERISATION_FRAME_PERIOD = Constants.LOOP_PERIOD;

  // === SDS Mk5i, off the manufacturer's layout drawing =========================================

  // The three ratios are the manufacturer's; which of them this robot runs is not confirmed, and
  // it is the 14T first-stage pinion that says R2. Swapping the pinion is the only change the
  // ratio needs, so the stages are written as their tooth counts.
  public static final double DRIVE_REDUCTION = (54.0 / 14.0) * (25.0 / 32.0) * (30.0 / 15.0);
  public static final double STEER_REDUCTION = 26.0;

  // === Provisional =============================================================================
  // Nothing below this line has been measured, and no chassis exists to measure it on. Every
  // number here is a starting value that a bring-up session replaces.

  // Nominal for the 4 in wheel. Odometry wants the rolling radius under load, which is smaller.
  public static final Distance WHEEL_RADIUS = Inches.of(2);
  // Centre to centre between the two modules on an axle: TRACK_WIDTH across the robot, WHEELBASE
  // along it. Both are distances between module centres, not the frame's outside dimensions.
  public static final Distance TRACK_WIDTH = Inches.of(23.5);
  public static final Distance WHEELBASE = Inches.of(23.5);

  public static final Current DRIVE_CURRENT_LIMIT = Amps.of(60);
  public static final Current STEER_CURRENT_LIMIT = Amps.of(40);

  // Competition weight with bumpers and battery.
  public static final Mass ROBOT_MASS = Pounds.of(125);

  // Everything the steer motor turns, about the module's steering axis. Nobody has weighed it.
  public static final MomentOfInertia STEER_INERTIA = KilogramSquareMeters.of(0.004);

  // The drive encoder counts motor rotations; conversion puts every drive quantity in metres, so
  // kV is Volts per metre per second and a setpoint is a wheel speed.
  public static final double DRIVE_POSITION_FACTOR =
      2 * Math.PI * WHEEL_RADIUS.in(Meters) / DRIVE_REDUCTION;
  // The primary encoder reports velocity in RPM, not rotations per second.
  public static final double DRIVE_VELOCITY_FACTOR = DRIVE_POSITION_FACTOR / 60;

  // The Thrifty analog is ratiometric and swings the full supply rail over one turn of the
  // module, and the Flex's analog input reads to that same rail, so the two cancel.
  public static final Voltage STEER_SENSOR_SPAN = Volts.of(5);
  // Volts to module rotations, which puts the sensor in [0, 1) and fixes the wrap range with it.
  public static final double STEER_POSITION_FACTOR = 1 / STEER_SENSOR_SPAN.in(Volts);
  public static final double STEER_VELOCITY_FACTOR = STEER_POSITION_FACTOR;

  // The nameplate free speed at this reduction, not a measurement of a chassis.
  public static final LinearVelocity MAX_VELOCITY =
      MetersPerSecond.of(
          DCMotor.getNeoVortex(1).freeSpeed / DRIVE_REDUCTION * WHEEL_RADIUS.in(Meters));

  // Half the diagonal between opposite modules: the radius the corner wheels travel on in a spin.
  public static final Distance DRIVE_RADIUS =
      Meters.of(Math.hypot(TRACK_WIDTH.in(Meters), WHEELBASE.in(Meters)) / 2);

  // The spin that puts the corner modules at MAX_VELOCITY. Asking for this and a translation at
  // once oversaturates the wheels, which desaturateWheelVelocities then scales back together.
  public static final AngularVelocity MAX_ANGULAR_VELOCITY =
      RadiansPerSecond.of(MAX_VELOCITY.in(MetersPerSecond) / DRIVE_RADIUS.in(Meters));

  // The offset a used controller sits at with nobody touching it. Below this the command is
  // exactly zero, because even a small omega steers all four modules to a rotation angle.
  public static final double DRIVER_DEADBAND = 0.05;

  // The width of the soft region in the stick curve. Fitted to the curve 604 publish rather than
  // chosen: over their plotted range this reproduces it to within the width of their line.
  public static final double DRIVER_CURVE_WIDTH = 0.35;

  // A SPARK told to apply a voltage holds it against battery sag, so the driver's stick means the
  // same wheel speed at the end of a match as at the start.
  public static final Voltage NOMINAL_VOLTAGE = Volts.of(12);

  // The pose estimator's trust in the wheels as standard deviations over [x, y, z, theta], so
  // larger is less trust. WPILib's own defaults, until a bring-up drives a known distance and
  // measures the drift.
  public static final Matrix<N4, N1> STATE_STD_DEVS = VecBuilder.fill(0.1, 0.1, 0.1, 0.1);

  // SwerveDrivePoseEstimator3d keeps its own 1.5 s of odometry and drops anything older, and its
  // BUFFER_DURATION is private so this cannot be read off it. Shorter here is a second staleness
  // cutoff carrying a different number from the one the estimator enforces.
  public static final Time YAW_RATE_HISTORY = Seconds.of(1.5);

  // A pose estimator tuned against a gyro that never wanders is tuned against a robot that does
  // not exist. Roughly a degree a minute, which is the order a Pigeon2 drifts at.
  public static final AngularVelocity GYRO_SIM_DRIFT = DegreesPerSecond.of(1.0 / 60);

  // Volts per metre per second squared at the wheel, handed to the SPARK as arbFeedforward beside
  // the velocity setpoint. Zero until SysId's dynamic test produces one, which leaves the term
  // present and inert rather than absent.
  public static final double DRIVE_KA = 0.0;

  // The module the analyser's columns come from, indexed in corner order: front left, front right,
  // back left, back right. All four are driven and this one is logged — a gain measured off one
  // powered wheel dragging three unpowered ones belongs to a machine we do not have.
  public static final int CHARACTERISED_MODULE = 0;

  public static final Velocity<VoltageUnit> DRIVE_RAMP_RATE = Volts.of(1).per(Second);
  public static final Voltage DRIVE_STEP_VOLTAGE = Volts.of(7);

  // Steer turns one module rather than a robot, and the whole of its test is the stretch before
  // break-away, so a ramp as steep as the drive's would be past the answer by the first sample.
  public static final Velocity<VoltageUnit> STEER_RAMP_RATE = Volts.of(0.25).per(Second);

  // Small because the azimuth axis has nothing in front of it — no profile, and the position
  // loop is not running during a test — so the step is a step against the stop. The carried
  // routine's own default is 7 V, which is a drivetrain's number on a 26:1 steering stage.
  public static final Voltage STEER_STEP_VOLTAGE = Volts.of(3);

  // A characterisation is supervised on the ground, so the timeout is the safety stop rather than
  // the test length: long enough to reach break-away, short enough to walk the robot back. It is
  // also the ramp's ceiling — rate times timeout, so 5 V for drive and 1.25 V for steer — and a
  // module that breaks away above its ceiling logs a test the analyser trims down to nothing.
  //
  // It is the dynamic step's duration as well, and that is the half that costs floor: five
  // seconds at DRIVE_STEP_VOLTAGE is on the order of fifteen metres of straight line. The
  // analyser trims dynamic data to a step window it computes for itself, so a shorter run loses
  // nothing but the distance — pick the number at the first bench session, with the room measured.
  public static final Time CHARACTERISATION_TIMEOUT = Seconds.of(5);

  // Two jobs, and it is long enough for both. Long enough for the steer loop to bring a module
  // round from any angle, because a wheel still slewing turns its own drive encoder through the
  // module's coupling and that motion lands in the low-voltage samples kS is fitted from. And long
  // enough for the robot to stop, at zero volts against the motor's own back-EMF — several
  // response timescales — because a step test begun on the move spends its first samples
  // arresting, which the analyser reads as measurement delay.
  public static final Time CHARACTERISATION_SETTLE = Seconds.of(1);

  // The rotation routine spins the robot on the spot, so its step is gentler than the drive's: a
  // step that translates a robot in a straight line spins it into whatever is beside it.
  public static final Velocity<VoltageUnit> ROTATION_RAMP_RATE = Volts.of(1).per(Second);
  public static final Voltage ROTATION_STEP_VOLTAGE = Volts.of(4);

  // Slow enough that the wheels do not slip, because slip is the one thing that breaks the
  // arithmetic: the measurement is wheel travel against the arc the gyro says the robot turned.
  public static final AngularVelocity WHEEL_RADIUS_OMEGA = RadiansPerSecond.of(0.1);
  public static final AngularAcceleration WHEEL_RADIUS_RAMP = RadiansPerSecondPerSecond.of(1);

  // One full turn of the robot before the estimate means anything. Below it, the fixed error in
  // where the modules were pointing at the start is a large share of the arc measured.
  public static final Angle WHEEL_RADIUS_MIN_ROTATION = Rotations.of(1);

  // Metres per second of correction per metre of error, and radians per second per radian. No path
  // has been driven on a chassis; the gains are a starting point that a bring-up replaces, and the
  // margin is the worst settling time the simulated paths take past their own duration, rounded up.
  public static final HolonomicPathFollower.Config PATH_FOLLOWER =
      new HolonomicPathFollower.Config(
          5.0, 5.0, 5.0, Meters.of(0.05), Degrees.of(2), Seconds.of(2));

  public record DriveMotorGains(double kP, double kS, double kV) {}

  // kS is the whole of steer's feedforward: kV is not applied in position mode and kA only in
  // MAXMotion, and steer takes no profile. dFilter is REVLib's derivative filter, whose javadoc
  // gives it no units and no range, so it is tuned against kD rather than after it.
  public record SteerMotorGains(double kP, double kD, double kS, double dFilter) {}

  public record ModuleGains(DriveMotorGains drive, SteerMotorGains steer) {}

  // No characterisation has run. kV is 12 V over the free speed at this reduction and kS is a
  // guess; both are the nameplate rather than a measurement.
  public static final ModuleGains REAL_GAINS =
      new ModuleGains(
          new DriveMotorGains(0.05, 0.15, 2.0), new SteerMotorGains(3.0, 0.05, 0.0, 0.0));

  // Chosen so the model tracks its setpoint. These are not a prediction of the real robot's gains,
  // and turning them until a test passes turns that test into a tautology. A characterisation run
  // in simulation fits the model it was given back to itself, so a gain fitted there describes the
  // model and never the robot.
  public static final ModuleGains SIM_GAINS =
      new ModuleGains(new DriveMotorGains(0.1, 0.0, 2.0), new SteerMotorGains(8.0, 0.0, 0.0, 0.0));

  public record SwerveModuleConfig(
      String name, int driveId, int steerId, Angle steerZeroOffset, Translation2d location) {}

  public record DriveConfig(
      SwerveModuleConfig frontLeft,
      SwerveModuleConfig frontRight,
      SwerveModuleConfig backLeft,
      SwerveModuleConfig backRight,
      int gyroId) {}

  private static final Distance HALF_TRACK = TRACK_WIDTH.div(2);
  private static final Distance HALF_BASE = WHEELBASE.div(2);

  public static final DriveConfig DRIVE =
      new DriveConfig(
          new SwerveModuleConfig(
              "FrontLeft",
              FRONT_LEFT_DRIVE_ID,
              FRONT_LEFT_STEER_ID,
              Rotations.of(0),
              new Translation2d(HALF_BASE, HALF_TRACK)),
          new SwerveModuleConfig(
              "FrontRight",
              FRONT_RIGHT_DRIVE_ID,
              FRONT_RIGHT_STEER_ID,
              Rotations.of(0),
              new Translation2d(HALF_BASE, HALF_TRACK.unaryMinus())),
          new SwerveModuleConfig(
              "BackLeft",
              BACK_LEFT_DRIVE_ID,
              BACK_LEFT_STEER_ID,
              Rotations.of(0),
              new Translation2d(HALF_BASE.unaryMinus(), HALF_TRACK)),
          new SwerveModuleConfig(
              "BackRight",
              BACK_RIGHT_DRIVE_ID,
              BACK_RIGHT_STEER_ID,
              Rotations.of(0),
              new Translation2d(HALF_BASE.unaryMinus(), HALF_TRACK.unaryMinus())),
          GYRO_ID);

  public static ModuleGains gains() {
    return RobotBase.isSimulation() ? SIM_GAINS : REAL_GAINS;
  }

  public static SwerveSimConfig simConfig() {
    // A quarter of the robot's mass reflected onto the wheel, not the wheel's own inertia: the
    // drive axis has to accelerate the robot.
    var driveInertia =
        KilogramSquareMeters.of(
            ROBOT_MASS.in(Kilograms) / 4 * Math.pow(WHEEL_RADIUS.in(Meters), 2));

    return new SwerveSimConfig(
        DRIVE.frontLeft().location(),
        DRIVE.frontRight().location(),
        DRIVE.backLeft().location(),
        DRIVE.backRight().location(),
        WHEEL_RADIUS,
        new SwerveSimConfig.Axis(
            DCMotor.getNeoVortex(1), DRIVE_REDUCTION, driveInertia, DRIVE_CURRENT_LIMIT),
        new SwerveSimConfig.Axis(
            DCMotor.getNeoVortex(1), STEER_REDUCTION, STEER_INERTIA, STEER_CURRENT_LIMIT));
  }

  private DriveConstants() {}
}
