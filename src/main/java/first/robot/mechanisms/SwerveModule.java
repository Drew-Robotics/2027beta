// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Celsius;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Rotations;
import static org.wpilib.units.Units.RotationsPerSecond;
import static org.wpilib.units.Units.Volts;

import com.revrobotics.PersistMode;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.ResetMode;
import com.revrobotics.spark.ClosedLoopSlot;
import com.revrobotics.spark.FeedbackSensor;
import com.revrobotics.spark.SparkAnalogSensor;
import com.revrobotics.spark.SparkClosedLoopController;
import com.revrobotics.spark.SparkClosedLoopController.ArbFFUnits;
import com.revrobotics.spark.SparkFlex;
import com.revrobotics.spark.SparkLowLevel.ControlType;
import com.revrobotics.spark.SparkLowLevel.MotorType;
import com.revrobotics.spark.config.FeedForwardConfig;
import com.revrobotics.spark.config.SparkBaseConfig.IdleMode;
import com.revrobotics.spark.config.SparkFlexConfig;
import first.robot.Constants;
import first.robot.DriveConstants;
import first.robot.DriveConstants.ModuleGains;
import first.robot.DriveConstants.SwerveModuleConfig;
import first.robot.Hardware;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.SwerveModuleAcceleration;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.util.MathUtil;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.AngularVelocity;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.LinearVelocity;
import org.wpilib.units.measure.Voltage;

final class SwerveModule {
  private final String name;
  private final SparkFlex driveMotor;
  private final SparkFlex steerMotor;
  private final RelativeEncoder driveEncoder;
  private final SparkAnalogSensor steerSensor;
  private final SparkClosedLoopController driveController;
  private final SparkClosedLoopController steerController;
  private final double steerOffsetRotations;
  private final TelemetryTable moduleLog;

  private SwerveModuleVelocity desired = new SwerveModuleVelocity();
  private double steerSetpointRotations;
  private boolean closingLoops;
  private boolean driveVoltageMode;
  private boolean steerVoltageMode;
  private double driveVolts;
  private double steerVolts;

  SwerveModule(SwerveModuleConfig config, ModuleGains gains, TelemetryTable log) {
    name = config.name();
    moduleLog = log;
    steerOffsetRotations = config.steerZeroOffset().in(Rotations);

    driveMotor = new SparkFlex(Constants.CAN_BUS.value, config.driveId(), MotorType.kBrushless);
    steerMotor = new SparkFlex(Constants.CAN_BUS.value, config.steerId(), MotorType.kBrushless);
    driveEncoder = driveMotor.getEncoder();
    steerSensor = steerMotor.getAnalog();
    driveController = driveMotor.getClosedLoopController();
    steerController = steerMotor.getClosedLoopController();

    Hardware.configureSpark(
        "Swerve" + name + "Drive",
        () ->
            driveMotor.configure(
                driveConfig(gains),
                ResetMode.kResetSafeParameters,
                PersistMode.kPersistParameters));
    Hardware.configureSpark(
        "Swerve" + name + "Steer",
        () ->
            steerMotor.configure(
                steerConfig(gains),
                ResetMode.kResetSafeParameters,
                PersistMode.kPersistParameters));

    moduleLog.keepDuplicates("DriveFaults");
    moduleLog.keepDuplicates("SteerFaults");
  }

  private static SparkFlexConfig driveConfig(ModuleGains gains) {
    var config = new SparkFlexConfig();
    config
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit((int) DriveConstants.DRIVE_CURRENT_LIMIT.in(Amps));
    config
        .encoder
        .positionConversionFactor(DriveConstants.DRIVE_POSITION_FACTOR)
        .velocityConversionFactor(DriveConstants.DRIVE_VELOCITY_FACTOR);
    config
        .closedLoop
        .feedbackSensor(FeedbackSensor.kPrimaryEncoder)
        .p(gains.drive().kP())
        // kS and kV live here and nowhere else. arbFeedforward carries an already-computed
        // voltage rather than a gain, so a term written in both places doubles and nothing throws.
        .apply(new FeedForwardConfig().sv(gains.drive().kS(), gains.drive().kV()));
    config
        .signals
        .primaryEncoderPositionPeriodMs(odometryFramePeriodMs())
        .primaryEncoderVelocityPeriodMs(odometryFramePeriodMs());
    diagnosticFrames(config);
    return config;
  }

  private static SparkFlexConfig steerConfig(ModuleGains gains) {
    var config = new SparkFlexConfig();
    config
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit((int) DriveConstants.STEER_CURRENT_LIMIT.in(Amps));
    config
        .analogSensor
        .positionConversionFactor(DriveConstants.STEER_POSITION_FACTOR)
        .velocityConversionFactor(DriveConstants.STEER_VELOCITY_FACTOR);
    config
        .closedLoop
        .feedbackSensor(FeedbackSensor.kAnalogSensor)
        .pid(gains.steer().kP(), 0, gains.steer().kD())
        .dFilter(gains.steer().dFilter())
        // kS alone: kV is documented as not applied in position mode and kA only in MAXMotion,
        // and both would configure clean and do nothing.
        .apply(new FeedForwardConfig().kS(gains.steer().kS()))
        // The analog runs 0 to 1 and drops to 0 every revolution with no accumulator, so a
        // non-wrapping loop sees a one-rotation error at the boundary and applies full output.
        .positionWrappingEnabled(true)
        .positionWrappingInputRange(0, 1);
    config
        .signals
        .analogPositionPeriodMs(odometryFramePeriodMs())
        .analogVelocityPeriodMs(odometryFramePeriodMs())
        .analogVoltagePeriodMs(odometryFramePeriodMs());
    diagnosticFrames(config);
    return config;
  }

  private static int odometryFramePeriodMs() {
    return (int) DriveConstants.ODOMETRY_FRAME_PERIOD.in(Milliseconds);
  }

  private static void diagnosticFrames(SparkFlexConfig config) {
    int faultMs = (int) DriveConstants.FAULT_FRAME_PERIOD.in(Milliseconds);
    int diagnosticMs = (int) DriveConstants.DIAGNOSTIC_FRAME_PERIOD.in(Milliseconds);
    config
        .signals
        .faultsPeriodMs(faultMs)
        .warningsPeriodMs(faultMs)
        .appliedOutputPeriodMs(diagnosticMs)
        .busVoltagePeriodMs(diagnosticMs)
        .outputCurrentPeriodMs(diagnosticMs)
        .motorTemperaturePeriodMs(diagnosticMs)
        // The controller's own copy of the setpoint separates a SPARK that never received one —
        // it browned out and came back at default frame rates — from a SPARK that cannot reach it.
        .setpointPeriodMs(diagnosticMs);
  }

  void setVelocity(SwerveModuleVelocity target) {
    command(resolve(target), 0, false);
  }

  void setVelocity(SwerveModuleVelocity target, SwerveModuleAcceleration acceleration) {
    // Resolved once, so the direction the feedforward is projected onto is the same one the wheel
    // is commanded in. Reading the sensor twice can straddle the 90-degree optimise boundary and
    // leave the feedforward pushing against the setpoint.
    var resolved = resolve(target);
    command(
        resolved, DriveConstants.DRIVE_KA * accelerationAlong(acceleration, resolved.angle), false);
  }

  void setOpenLoopVelocity(SwerveModuleVelocity target) {
    command(resolve(target), 0, true);
  }

  private SwerveModuleVelocity resolve(SwerveModuleVelocity target) {
    var angle = getAngle();
    return target.optimize(angle).cosineScale(angle);
  }

  private void command(SwerveModuleVelocity resolved, double arbFeedforwardVolts, boolean open) {
    desired = resolved;
    steerSetpointRotations = toSensorRotations(desired.angle, steerOffsetRotations);
    driveVoltageMode = open;

    if (open) {
      driveVolts = openLoopVolts(desired.velocity);
      driveMotor.setVoltage(driveVolts);
    } else {
      driveController.setSetpoint(
          desired.velocity,
          ControlType.kVelocity,
          ClosedLoopSlot.kSlot0,
          arbFeedforwardVolts,
          ArbFFUnits.kVoltage);
    }
    steerVoltageMode = false;
    steerController.setSetpoint(steerSetpointRotations, ControlType.kPosition);
    closingLoops = true;
  }

  // The drive loop is what a drive characterisation measures, so the ramp is written as volts and
  // no loop is closed around it. Steer holds the azimuth the ramp is meant to push along — forward
  // for a straight-line test, tangent to the spin circle for a rotation one — because a ramp
  // measured against whatever angle the modules were parked at measures a different manoeuvre.
  //
  // The azimuth is taken as given rather than optimised: reverse is the routine's negative
  // voltage, and a wheel flipped half a turn to shorten the slew would answer it the wrong way.
  void characteriseDrive(Rotation2d azimuth, Voltage volts) {
    driveVolts = volts.in(Volts);
    driveVoltageMode = true;
    driveMotor.setVoltage(driveVolts);

    desired = new SwerveModuleVelocity(0, azimuth);
    steerSetpointRotations = toSensorRotations(azimuth, steerOffsetRotations);
    steerVoltageMode = false;
    steerController.setSetpoint(steerSetpointRotations, ControlType.kPosition);
    closingLoops = true;
  }

  void characteriseSteer(Voltage volts) {
    steerVolts = volts.in(Volts);
    steerVoltageMode = true;
    steerMotor.setVoltage(steerVolts);

    desired = new SwerveModuleVelocity(0, getAngle());
    // No loop is reaching for anything here, so the logged setpoint follows the module: a frozen
    // target against a turning wheel reads as a setpoint the controller cannot reach.
    steerSetpointRotations = toSensorRotations(desired.angle, steerOffsetRotations);
    driveVolts = 0;
    driveVoltageMode = true;
    driveMotor.setVoltage(0);
    closingLoops = true;
  }

  // Applied output and bus voltage share Status0 with output current and motor temperature, so
  // raising the two the characterisation column needs raises those two for no extra frame.
  void instrumentDrive(boolean raised) {
    Hardware.configureSpark(
        "Swerve" + name + "DriveFrames",
        () ->
            driveMotor.configure(
                appliedOutputFrames(raised),
                ResetMode.kNoResetSafeParameters,
                PersistMode.kNoPersistParameters));
  }

  void instrumentSteer(boolean raised) {
    Hardware.configureSpark(
        "Swerve" + name + "SteerFrames",
        () ->
            steerMotor.configure(
                appliedOutputFrames(raised),
                ResetMode.kNoResetSafeParameters,
                PersistMode.kNoPersistParameters));
  }

  // A fresh config each time, because the period setters keep the smaller of the requested and the
  // value already in the object they are called on: restoring through one that has been raised
  // leaves it raised.
  private static SparkFlexConfig appliedOutputFrames(boolean raised) {
    var config = new SparkFlexConfig();
    int periodMs =
        (int)
            (raised
                    ? DriveConstants.CHARACTERISATION_FRAME_PERIOD
                    : DriveConstants.DIAGNOSTIC_FRAME_PERIOD)
                .in(Milliseconds);
    config.signals.appliedOutputPeriodMs(periodMs).busVoltagePeriodMs(periodMs);
    return config;
  }

  // kNoResetSafeParameters and kNoPersistParameters, both load-bearing: the first leaves every
  // setting this config does not name alone, and the second is what makes a tuned gain die at the
  // next power cycle rather than becoming an undocumented property of one controller.
  void applyGains(ModuleGains gains) {
    Hardware.configureSpark(
        "Swerve" + name + "DriveGains",
        () ->
            driveMotor.configure(
                driveConfig(gains),
                ResetMode.kNoResetSafeParameters,
                PersistMode.kNoPersistParameters));
    Hardware.configureSpark(
        "Swerve" + name + "SteerGains",
        () ->
            steerMotor.configure(
                steerConfig(gains),
                ResetMode.kNoResetSafeParameters,
                PersistMode.kNoPersistParameters));
  }

  // SwerveModuleAcceleration carries an unsigned magnitude with the direction in its angle, so the
  // wheel's own share of it is the projection onto the direction the wheel is being driven in —
  // negative when the wheel is braking, which the magnitude alone cannot say.
  static double accelerationAlong(SwerveModuleAcceleration acceleration, Rotation2d wheel) {
    return acceleration.acceleration * acceleration.angle.minus(wheel).getCos();
  }

  // The share of the free speed the driver asked for, spent as the same share of the rail. It is
  // the free-speed relationship inverted, which is what makes a stick position mean a wheel speed
  // without a loop measuring anything.
  static double openLoopVolts(double velocity) {
    return DriveConstants.NOMINAL_VOLTAGE.in(Volts)
        * velocity
        / DriveConstants.MAX_VELOCITY.in(MetersPerSecond);
  }

  void stop() {
    // Both SPARKs idle in brake, so this is not a coast: dropping the output leaves the short
    // across the motor, which resists a shove without driving back against one. A zero velocity
    // setpoint would do the second, and that is the brake a driver cannot drive out of.
    driveMotor.stopMotor();
    steerMotor.stopMotor();
    desired = new SwerveModuleVelocity(0, getAngle());
    // The logged setpoint's whole job is to tell a setpoint the SPARK never received from one it
    // cannot reach, and a stale angle against a coasting module reads as the second.
    steerSetpointRotations = toSensorRotations(desired.angle, steerOffsetRotations);
    driveVolts = 0;
    steerVolts = 0;
    closingLoops = false;
  }

  Distance getDriveDistance() {
    return Meters.of(driveEncoder.getPosition().get());
  }

  LinearVelocity getDriveSpeed() {
    return MetersPerSecond.of(driveEncoder.getVelocity().get());
  }

  // What the controller put on the motor, which is below what it was asked for whenever the
  // current limit is binding. Applied output is a duty cycle, so the rail comes back with it.
  Voltage getDriveAppliedVoltage() {
    return Volts.of(driveMotor.getAppliedOutput().get() * driveMotor.getBusVoltage().get());
  }

  Voltage getSteerAppliedVoltage() {
    return Volts.of(steerMotor.getAppliedOutput().get() * steerMotor.getBusVoltage().get());
  }

  // The sensor's own reading, before the module offset: it is the signal the steer loop closes on.
  // It runs 0 to 1 and wraps, so a reverse ramp on a module parked near zero steps a whole
  // rotation on its second sample. The feedforward fit is on voltage against velocity and does not
  // see it; anything read off the position column does.
  Angle getSteerRotation() {
    return Rotations.of(steerSensor.getPosition().get());
  }

  AngularVelocity getSteerRate() {
    return RotationsPerSecond.of(steerSensor.getVelocity().get());
  }

  String getName() {
    return name;
  }

  Rotation2d getAngle() {
    return Rotation2d.fromRotations(steerSensor.getPosition().get() - steerOffsetRotations);
  }

  SwerveModuleVelocity getVelocity() {
    return new SwerveModuleVelocity(driveEncoder.getVelocity().get(), getAngle());
  }

  SwerveModulePosition getPosition() {
    return new SwerveModulePosition(driveEncoder.getPosition().get(), getAngle());
  }

  SwerveModuleVelocity getDesiredVelocity() {
    return desired;
  }

  void log() {
    moduleLog.log("DriveOutput", driveMotor.getAppliedOutput().get());
    moduleLog.log("DriveCurrent", Amps.of(driveMotor.getOutputCurrent().get()));
    moduleLog.log("DriveTemp", Celsius.of(driveMotor.getMotorTemperature().get()));
    // The module angle, not the sensor rotations the SPARK was handed: the sensor frame runs
    // [0, 1) and SteerAngle runs [-0.5, 0.5), so the logged pair could not be subtracted.
    moduleLog.log("SteerSetpoint", desired.angle.getMeasure());
    moduleLog.log("SteerAngle", getAngle().getMeasure());
    moduleLog.log("SteerCurrent", Amps.of(steerMotor.getOutputCurrent().get()));
    moduleLog.log("SteerTemp", Celsius.of(steerMotor.getMotorTemperature().get()));
    // REVLib's packed fault word; SparkBase.Faults names the bits at the SHA this log carries.
    moduleLog.log("DriveFaults", driveMotor.getFaults().get().rawBits);
    moduleLog.log("SteerFaults", steerMotor.getFaults().get().rawBits);
  }

  // Everything below is for the simulation update hook, which builds this module's sensor sims
  // and models the loops these setpoints were written to.

  SparkFlex getDriveMotor() {
    return driveMotor;
  }

  SparkFlex getSteerMotor() {
    return steerMotor;
  }

  boolean isClosingLoops() {
    return closingLoops;
  }

  boolean isDriveVoltageMode() {
    return driveVoltageMode;
  }

  boolean isSteerVoltageMode() {
    return steerVoltageMode;
  }

  double getDriveSetpoint() {
    return desired.velocity;
  }

  double getDriveVolts() {
    return driveVolts;
  }

  double getSteerVolts() {
    return steerVolts;
  }

  double getSteerSetpoint() {
    return steerSetpointRotations;
  }

  double toSensorRotations(Rotation2d azimuth) {
    return toSensorRotations(azimuth, steerOffsetRotations);
  }

  // getRotations() returns [-0.5, 0.5] and the converted sensor reads [0, 1): the two agree on the
  // first half turn and differ by exactly one rotation on the second, and a value one rotation out
  // is outside the configured wrapping range entirely, so wrapping does not rescue it.
  static double toSensorRotations(Rotation2d azimuth, double offsetRotations) {
    return MathUtil.inputModulus(azimuth.getRotations() + offsetRotations, 0, 1);
  }
}
