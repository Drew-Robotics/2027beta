// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Celsius;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Rotations;
import static org.wpilib.units.Units.RotationsPerSecond;
import static org.wpilib.units.Units.Seconds;
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
import org.wpilib.driverstation.RobotState;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.SwerveModuleAcceleration;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.util.MathUtil;
import org.wpilib.system.Timer;
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
  private final RelativeEncoder steerEncoder;
  private final SparkAnalogSensor steerSensor;
  private final SparkClosedLoopController driveController;
  private final SparkClosedLoopController steerController;
  private final double steerOffsetRotations;
  private final TelemetryTable moduleLog;

  private SwerveModuleVelocity desired = new SwerveModuleVelocity();
  private double steerSetpointMotorRotations;
  private boolean closingLoops;
  private boolean driveVoltageMode;
  private boolean steerVoltageMode;
  private double driveVolts;
  private double steerVolts;
  private double lastSeedTimestamp;
  private int seedCount;
  private boolean seededForReset;

  SwerveModule(SwerveModuleConfig config, ModuleGains gains, TelemetryTable log) {
    name = config.name();
    moduleLog = log;
    steerOffsetRotations = config.steerZeroOffset().in(Rotations);

    driveMotor = new SparkFlex(Constants.CAN_BUS, config.driveId(), MotorType.kBrushless);
    steerMotor = new SparkFlex(Constants.CAN_BUS, config.steerId(), MotorType.kBrushless);
    driveEncoder = driveMotor.getEncoder();
    steerEncoder = steerMotor.getEncoder();
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

    seedSteerFromAbsolute();

    moduleLog.keepDuplicates("DriveFaults");
    moduleLog.keepDuplicates("SteerFaults");
    moduleLog.keepDuplicates("SteerStickyWarnings");
  }

  private static SparkFlexConfig driveConfig(ModuleGains gains) {
    var onboard = DriveConstants.onboardGains(gains).drive();
    var config = new SparkFlexConfig();
    config
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit((int) DriveConstants.DRIVE_CURRENT_LIMIT.in(Amps));
    config
        .closedLoop
        .feedbackSensor(FeedbackSensor.kPrimaryEncoder)
        .p(onboard.kP())
        // kS and kV live here and nowhere else. arbFeedforward carries an already-computed
        // voltage rather than a gain, so a term written in both places doubles and nothing throws.
        .apply(new FeedForwardConfig().sv(onboard.kS(), onboard.kV()));
    config
        .signals
        .primaryEncoderPositionPeriodMs(odometryFramePeriodMs())
        .primaryEncoderVelocityPeriodMs(odometryFramePeriodMs());
    diagnosticFrames(config);
    return config;
  }

  private static SparkFlexConfig steerConfig(ModuleGains gains) {
    var onboard = DriveConstants.onboardGains(gains).steer();
    var config = new SparkFlexConfig();
    config
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit((int) DriveConstants.STEER_CURRENT_LIMIT.in(Amps));
    config
        .closedLoop
        // The motor's own encoder, seeded from the analog: DriveConstants.steerSetpoint is what
        // carries the shortest path, and says why the device cannot.
        .feedbackSensor(FeedbackSensor.kPrimaryEncoder)
        // Stated rather than left to kResetSafeParameters, because applyGains does not reset.
        .positionWrappingEnabled(false)
        .pid(onboard.kP(), 0, onboard.kD())
        .dFilter(onboard.dFilter())
        // kS alone: kV is documented as not applied in position mode and kA only in MAXMotion,
        // and both would configure clean and do nothing.
        .apply(new FeedForwardConfig().kS(onboard.kS()));
    config
        .signals
        .primaryEncoderPositionPeriodMs(odometryFramePeriodMs())
        .primaryEncoderVelocityPeriodMs(odometryFramePeriodMs());
    // The analog is the seed and a cross-check, not the loop's feedback, so it rides with the
    // diagnostics. Position alone: it shares its frame with the analog's velocity and voltage, and
    // setting one of a group sets the group. The seed's stillness gate reads the encoder, which is
    // already at the odometry rate, rather than this.
    config.signals.analogPositionPeriodMs(
        (int) DriveConstants.DIAGNOSTIC_FRAME_PERIOD.in(Milliseconds));
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

  void setTarget(SwerveModuleVelocity target) {
    command(resolve(target), 0, false);
  }

  void setTarget(SwerveModuleVelocity target, SwerveModuleAcceleration acceleration) {
    // Resolved once, so the direction the feedforward is projected onto is the same one the wheel
    // is commanded in. Reading the sensor twice can straddle the 90-degree optimise boundary and
    // leave the feedforward pushing against the setpoint.
    var resolved = resolve(target);
    command(
        resolved, DriveConstants.DRIVE_KA * accelerationAlong(acceleration, resolved.angle), false);
  }

  void setOpenLoopTarget(SwerveModuleVelocity target) {
    command(resolve(target), 0, true);
  }

  private SwerveModuleVelocity resolve(SwerveModuleVelocity target) {
    var angle = getAngle();
    return target.optimize(angle).cosineScale(angle);
  }

  private void command(SwerveModuleVelocity resolved, double arbFeedforwardVolts, boolean open) {
    desired = resolved;
    steerSetpointMotorRotations = toSteerSetpoint(desired.angle);
    driveVoltageMode = open;

    if (open) {
      driveVolts = openLoopVolts(desired.velocity);
      driveMotor.setVoltage(driveVolts);
    } else {
      driveController.setSetpoint(
          desired.velocity / DriveConstants.DRIVE_VELOCITY_FACTOR,
          ControlType.kVelocity,
          ClosedLoopSlot.kSlot0,
          arbFeedforwardVolts,
          ArbFFUnits.kVoltage);
    }
    steerVoltageMode = false;
    steerController.setSetpoint(steerSetpointMotorRotations, ControlType.kPosition);
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
    steerSetpointMotorRotations = toSteerSetpoint(azimuth);
    steerVoltageMode = false;
    steerController.setSetpoint(steerSetpointMotorRotations, ControlType.kPosition);
    closingLoops = true;
  }

  void characteriseSteer(Voltage volts) {
    steerVolts = volts.in(Volts);
    steerVoltageMode = true;
    steerMotor.setVoltage(steerVolts);

    desired = new SwerveModuleVelocity(0, getAngle());
    // No loop is reaching for anything here, so the logged setpoint follows the module: a frozen
    // target against a turning wheel reads as a setpoint the controller cannot reach.
    steerSetpointMotorRotations = steerEncoder.getPosition().get();
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

  // Build a fresh config: period setters cannot restore a lower period.
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

  // Do not reset unspecified settings or persist temporary tuning changes.
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
    steerSetpointMotorRotations = steerEncoder.getPosition().get();
    driveVolts = 0;
    steerVolts = 0;
    closingLoops = false;
  }

  // The absolute sensor's own reading with the module offset taken out: what the steer encoder is
  // seeded from, and the only azimuth that survives a SPARK losing its count.
  Rotation2d getAbsoluteAngle() {
    return Rotation2d.fromRotations(
        steerSensor.getPosition().get() * DriveConstants.STEER_SENSOR_POSITION_FACTOR
            - steerOffsetRotations);
  }

  void seedSteerFromAbsolute() {
    double motorRotations = DriveConstants.steerMotorRotations(getAbsoluteAngle().getRotations());
    Hardware.write("Swerve" + name + "SteerSeed", () -> steerEncoder.setPosition(motorRotations));
    lastSeedTimestamp = Timer.getTimestamp();
    seedCount++;
  }

  // A SPARK that reset is closing its loop on a count that means nothing, so it is reseeded at
  // once, enabled or not: a module reporting an angle it is not at drives the robot somewhere
  // else. Everything else waits for the robot to be disabled and the module to be still.
  void updateSteerSeed() {
    boolean hasReset = steerMotor.getStickyWarnings().get().hasReset;
    // On the edge. clearFaults can fail, and a level test would then reseed every loop, mid-slew,
    // for as long as the bit stayed up. Clearing it is also what makes the *next* reset visible,
    // which is why the sticky word is logged: this is the one thing that erases it.
    if (hasReset && !seededForReset) {
      steerMotor.clearFaults();
      seedSteerFromAbsolute();
    }
    seededForReset = hasReset;
    // No guard on hasReset here: a bit that stayed up because clearFaults failed must not also
    // cost the module its periodic reseed. The period gate is what stops a second seed this loop,
    // because the one above has already stamped it.
    if (!RobotState.isDisabled()
        || Timer.getTimestamp() - lastSeedTimestamp
            < DriveConstants.STEER_SEED_PERIOD.in(Seconds)) {
      return;
    }
    // Seeding mid-slew writes an angle the module has already left, and the wheel coasts for a
    // while after a match: the offset that lands is the travel between the read and the write.
    if (Math.abs(getSteerRate().in(RadiansPerSecond))
        <= DriveConstants.STEER_SEED_MAX_RATE.in(RadiansPerSecond)) {
      seedSteerFromAbsolute();
    }
  }

  Distance getDriveDistance() {
    return Meters.of(driveEncoder.getPosition().get() * DriveConstants.DRIVE_POSITION_FACTOR);
  }

  LinearVelocity getDriveSpeed() {
    return MetersPerSecond.of(
        driveEncoder.getVelocity().get() * DriveConstants.DRIVE_VELOCITY_FACTOR);
  }

  // What the controller put on the motor, which is below what it was asked for whenever the
  // current limit is binding. Applied output is a duty cycle, so the rail comes back with it.
  Voltage getDriveAppliedVoltage() {
    return Volts.of(driveMotor.getAppliedOutput().get() * driveMotor.getBusVoltage().get());
  }

  Voltage getSteerAppliedVoltage() {
    return Volts.of(steerMotor.getAppliedOutput().get() * steerMotor.getBusVoltage().get());
  }

  // The signal the steer loop closes on, in module rotations. It accumulates rather than wrapping,
  // so a characterisation ramp that carries a module past zero reads as continuous travel instead
  // of stepping a whole rotation part way through.
  Angle getSteerRotation() {
    return Rotations.of(
        steerEncoder.getPosition().get() * DriveConstants.STEER_MOTOR_POSITION_FACTOR);
  }

  AngularVelocity getSteerRate() {
    return RotationsPerSecond.of(
        steerEncoder.getVelocity().get() * DriveConstants.STEER_MOTOR_VELOCITY_FACTOR);
  }

  String getName() {
    return name;
  }

  Rotation2d getAngle() {
    return Rotation2d.fromRotations(
        steerEncoder.getPosition().get() * DriveConstants.STEER_MOTOR_POSITION_FACTOR);
  }

  SwerveModuleVelocity getMeasuredVelocity() {
    return new SwerveModuleVelocity(getDriveSpeed().in(MetersPerSecond), getAngle());
  }

  SwerveModulePosition getPosition() {
    return new SwerveModulePosition(getDriveDistance().in(Meters), getAngle());
  }

  SwerveModuleVelocity getDesiredVelocity() {
    return desired;
  }

  void log() {
    moduleLog.log("DriveOutput", driveMotor.getAppliedOutput().get());
    moduleLog.log("DriveCurrent", Amps.of(driveMotor.getOutputCurrent().get()));
    moduleLog.log("DriveTemp", Celsius.of(driveMotor.getMotorTemperature().get()));
    moduleLog.log("SteerSetpoint", desired.angle.getMeasure());
    moduleLog.log("SteerAngle", getAngle().getMeasure());
    // The seed against what it seeded: the two disagreeing by more than the reduction's backlash
    // is a count that drifted, and there is nothing else in the log that would say so.
    moduleLog.log("SteerAbsolute", getAbsoluteAngle().getMeasure());
    // The seed count is the only record that a reset happened, because reseeding clears the
    // sticky word that asked for it; the word itself is logged so the clear erases nothing that
    // was not already written down.
    moduleLog.log("SteerSeeds", seedCount);
    moduleLog.log("SteerStickyWarnings", steerMotor.getStickyWarnings().get().rawBits);
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

  // In RPM and motor rotations: the numbers the SPARK was handed, not the metres and module
  // angles they were written from.
  double getDriveSetpoint() {
    return desired.velocity / DriveConstants.DRIVE_VELOCITY_FACTOR;
  }

  double getDriveVolts() {
    return driveVolts;
  }

  double getSteerVolts() {
    return steerVolts;
  }

  double getSteerSetpoint() {
    return steerSetpointMotorRotations;
  }

  private double toSteerSetpoint(Rotation2d azimuth) {
    return DriveConstants.steerSetpoint(steerEncoder.getPosition().get(), azimuth);
  }

  double toSensorRotations(Rotation2d azimuth) {
    return toSensorRotations(azimuth, steerOffsetRotations);
  }

  // What the absolute sensor reads at a given module angle: getRotations() returns [-0.5, 0.5] and
  // the analog runs [0, 1), so the two agree on the first half turn and differ by exactly one
  // rotation on the second. Only the seed and the simulation's sensor feed go through here; the
  // loop's setpoint does not, because the encoder it closes on has no such range.
  static double toSensorRotations(Rotation2d azimuth, double offsetRotations) {
    return MathUtil.inputModulus(azimuth.getRotations() + offsetRotations, 0, 1);
  }
}
