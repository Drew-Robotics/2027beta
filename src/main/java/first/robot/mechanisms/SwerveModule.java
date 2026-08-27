// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Celsius;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Rotations;

import com.revrobotics.PersistMode;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.ResetMode;
import com.revrobotics.spark.FeedbackSensor;
import com.revrobotics.spark.SparkAnalogSensor;
import com.revrobotics.spark.SparkClosedLoopController;
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
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.util.MathUtil;
import org.wpilib.telemetry.TelemetryTable;

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
    var angle = getAngle();
    desired = target.optimize(angle).cosineScale(angle);
    steerSetpointRotations = toSensorRotations(desired.angle, steerOffsetRotations);

    driveController.setSetpoint(desired.velocity, ControlType.kVelocity);
    steerController.setSetpoint(steerSetpointRotations, ControlType.kPosition);
    closingLoops = true;
  }

  void stop() {
    // A zero velocity setpoint would hold the wheel against a shove, which is the brake a driver
    // cannot drive out of. Dropping the output is what leaves the robot pushable.
    driveMotor.stopMotor();
    steerMotor.stopMotor();
    desired = new SwerveModuleVelocity(0, getAngle());
    closingLoops = false;
  }

  Rotation2d getAngle() {
    return Rotation2d.fromRotations(steerSensor.getPosition().get() - steerOffsetRotations);
  }

  SwerveModuleVelocity getVelocity() {
    return new SwerveModuleVelocity(driveEncoder.getVelocity().get(), getAngle());
  }

  SwerveModuleVelocity getDesiredVelocity() {
    return desired;
  }

  void log() {
    moduleLog.log("DriveOutput", driveMotor.getAppliedOutput().get());
    moduleLog.log("DriveCurrent", Amps.of(driveMotor.getOutputCurrent().get()));
    moduleLog.log("DriveTemp", Celsius.of(driveMotor.getMotorTemperature().get()));
    moduleLog.log("SteerSetpoint", Rotations.of(steerSetpointRotations));
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

  double getDriveSetpoint() {
    return desired.velocity;
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
