// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Radians;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Seconds;
import static org.wpilib.units.Units.Volts;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.CANBus;
import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.hardware.Pigeon2;
import com.ctre.phoenix6.sim.Pigeon2SimState;
import com.revrobotics.sim.SparkAnalogSensorSim;
import com.revrobotics.sim.SparkRelativeEncoderSim;
import first.robot.Constants;
import first.robot.DriveConstants;
import first.robot.DriveConstants.DriveConfig;
import first.robot.DriveConstants.SwerveModuleConfig;
import first.robot.Hardware;
import first.robot.HolonomicPathFollower;
import first.robot.sim.OnboardLoopSim;
import first.robot.sim.SwerveDriveSim;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.wpilib.command3.Command;
import org.wpilib.command3.Mechanism;
import org.wpilib.command3.NeedsNameBuilderStage;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.button.CommandGamepad;
import org.wpilib.framework.RobotBase;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.simulation.RoboRioSim;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.Angle;

public class Drive implements Mechanism {
  private static final int MODULES = 4;
  private static final double SUB_STEP = DriveConstants.CONTROLLER_PERIOD.in(Seconds);
  private static final int SUB_STEPS =
      (int) Math.round(Constants.LOOP_PERIOD.in(Seconds) / SUB_STEP);
  private static final int PHOENIX_ATTEMPTS = 5;

  private final SwerveModule[] modules = new SwerveModule[MODULES];
  private final SwerveDriveKinematics kinematics;
  private final Pigeon2 gyro;
  private final Function<String, HolonomicTrajectory> trajectories;
  private final Supplier<Pose2d> pose;
  private final TelemetryTable chassisLog;
  private final TelemetryTable moduleLog;
  private final TelemetryTable odometryLog;
  private final TelemetryTable followingLog;
  private final TelemetryTable autoLog;
  private final TelemetryTable simLog;
  private final Scheduler scheduler;

  private ChassisVelocities desiredVelocities = new ChassisVelocities();
  private boolean pathTimedOut;

  // The vendor plumbing, and the one place in this project allowed to be saturated with it.
  // Everything it drives is in first.robot.sim, which imports no vendor type at all.
  private final SwerveDriveSim physics;
  private final OnboardLoopSim[] driveLoops = new OnboardLoopSim[MODULES];
  private final OnboardLoopSim[] steerLoops = new OnboardLoopSim[MODULES];
  private final SparkRelativeEncoderSim[] driveEncoderSims = new SparkRelativeEncoderSim[MODULES];
  private final SparkAnalogSensorSim[] steerSensorSims = new SparkAnalogSensorSim[MODULES];
  private final Pigeon2SimState gyroSim;
  private Angle simDrift;
  private Angle simYaw;
  private Rotation2d lastTrueRotation;

  // trajectories is a lookup into a cache somebody else filled, and pose is a read-only view of
  // the estimate. Neither hands this class an estimator, so it still cannot reset pose and still
  // never learns cameras exist.
  public Drive(
      DriveConfig config,
      Function<String, HolonomicTrajectory> trajectories,
      Supplier<Pose2d> pose,
      TelemetryTable log,
      TelemetryTable autoLog,
      TelemetryTable simLog,
      Scheduler scheduler) {
    this.trajectories = trajectories;
    this.pose = pose;
    this.autoLog = autoLog;
    this.simLog = simLog;
    this.scheduler = scheduler;
    chassisLog = log.getTable("Chassis");
    moduleLog = log.getTable("Modules");
    odometryLog = log.getTable("Odometry");
    followingLog = log.getTable("Following");

    var gains = DriveConstants.gains();
    var corners = corners(config);
    for (int i = 0; i < MODULES; i++) {
      modules[i] = new SwerveModule(corners[i], gains, moduleLog.getTable(corners[i].name()));
    }
    kinematics =
        new SwerveDriveKinematics(
            Arrays.stream(corners).map(SwerveModuleConfig::location).toArray(Translation2d[]::new));

    gyro = new Pigeon2(config.gyroId(), CANBus.systemcore(Constants.CAN_BUS.value));
    Hardware.configurePhoenix(
        "SwerveGyro", () -> gyro.getConfigurator().apply(new Pigeon2Configuration()));
    // Every one of these publishes below the loop rate at its Phoenix default — the yaw rate at
    // 10 Hz — so odometry unraised reads the same frame several times over and counts the repeats
    // as new information.
    Hardware.configurePhoenix(
        "SwerveGyroSignals",
        () ->
            BaseStatusSignal.setUpdateFrequencyForAll(
                Constants.LOOP_PERIOD.asFrequency(),
                gyro.getYaw(),
                gyro.getPitch(),
                gyro.getRoll(),
                gyro.getAngularVelocityZWorld()));

    if (RobotBase.isSimulation()) {
      physics = new SwerveDriveSim(DriveConstants.simConfig());
      gyroSim = gyro.getSimState();
      simDrift = Radians.zero();
      simYaw = Radians.zero();
      lastTrueRotation = physics.truePose().getRotation();
      for (int i = 0; i < MODULES; i++) {
        driveLoops[i] =
            OnboardLoopSim.velocity(gains.drive().kP(), gains.drive().kS(), gains.drive().kV());
        steerLoops[i] =
            OnboardLoopSim.position(
                gains.steer().kP(), gains.steer().kD(), gains.steer().dFilter(), 0, 1);
        // Built below the SPARK it names: a sensor sim whose device does not resolve drops every
        // write, and the mechanism's encoders then read zero forever with nothing thrown.
        driveEncoderSims[i] = new SparkRelativeEncoderSim(modules[i].getDriveMotor());
        steerSensorSims[i] = new SparkAnalogSensorSim(modules[i].getSteerMotor());
      }
    } else {
      physics = null;
      gyroSim = null;
    }
  }

  private static SwerveModuleConfig[] corners(DriveConfig config) {
    return new SwerveModuleConfig[] {
      config.frontLeft(), config.frontRight(), config.backLeft(), config.backRight()
    };
  }

  @Override
  public Scheduler getRegisteredScheduler() {
    return scheduler;
  }

  @Override
  public Command idle() {
    return runRepeatedly(this::stopModules)
        .withPriority(Command.LOWEST_PRIORITY)
        .named("Drive.Idle");
  }

  public Command driveRobotRelative(Supplier<ChassisVelocities> velocities) {
    return robotRelative(velocities, this::setVelocities).named("Drive.DriveRobotRelative");
  }

  public Command driveFieldRelative(Supplier<ChassisVelocities> velocities) {
    return fieldRelative(velocities, this::setVelocities).named("Drive.DriveFieldRelative");
  }

  // The estimator's heading, not the gyro's. The two differ by whatever a resetPose introduced —
  // Odometry3d assigns the pose and integrates gyro deltas onto it — and a follower whose error is
  // measured against the estimate has to be converted back in that same frame. The single-argument
  // form above stays on the gyro deliberately: a driver's forward should not jump when a vision
  // update moves the estimate.
  public Command driveFieldRelative(
      Supplier<ChassisVelocities> velocities, Supplier<ChassisAccelerations> accelerations) {
    return run(coroutine -> {
          while (true) {
            var heading = pose.get().getRotation();
            var field = velocities.get();
            setVelocities(
                field.toRobotRelative(heading), accelerations.get().toRobotRelative(heading));
            coroutine.yield();
          }
        })
        .whenCanceled(this::stopModules)
        .named("Drive.DriveFieldRelative");
  }

  public Command followPath(String pathName) {
    return run(coroutine -> {
          var follower =
              new HolonomicPathFollower(
                  trajectories.apply(pathName),
                  pose,
                  Timer::getTimestamp,
                  DriveConstants.PATH_FOLLOWER,
                  followingLog);
          // A timeout left over from the previous path is indistinguishable from this one's until
          // this one is over.
          pathTimedOut = false;
          followingLog.log("TimedOut", false);
          autoLog.log("PlannedPath", follower.plannedPath(), Pose2d.struct);

          coroutine.fork(driveFieldRelative(follower::next, follower::acceleration));
          var result = coroutine.waitUntil(follower::isDone, follower.timeout());
          pathTimedOut = result.timedOut();
          followingLog.log("TimedOut", result.timedOut());
        })
        .whenCanceled(this::stopModules)
        .named("Drive.FollowPath[" + pathName + "]");
  }

  // Open loop, unlike everything else that drives: a velocity loop answers a shove by spinning the
  // wheel back to its setpoint, and a driver reads that as the robot arguing. A voltage does what
  // the stick did.
  public Command driverControl(CommandGamepad driver) {
    return fieldRelative(() -> driverVelocities(driver), this::setOpenLoopVelocities)
        .named("Drive.DriverControl");
  }

  private NeedsNameBuilderStage robotRelative(
      Supplier<ChassisVelocities> velocities, Consumer<ChassisVelocities> output) {
    return run(coroutine -> {
          while (true) {
            output.accept(velocities.get());
            coroutine.yield();
          }
        })
        .whenCanceled(this::stopModules);
  }

  private NeedsNameBuilderStage fieldRelative(
      Supplier<ChassisVelocities> velocities, Consumer<ChassisVelocities> output) {
    return run(coroutine -> {
          while (true) {
            output.accept(velocities.get().toRobotRelative(getGyroHeading().toRotation2d()));
            coroutine.yield();
          }
        })
        .whenCanceled(this::stopModules);
  }

  // Away from the driver station is +x and to the driver's left is +y, and both stick axes read
  // the other way round.
  private static ChassisVelocities driverVelocities(CommandGamepad driver) {
    return new ChassisVelocities(
        DriveConstants.MAX_VELOCITY.times(stick(-driver.getLeftY())),
        DriveConstants.MAX_VELOCITY.times(stick(-driver.getLeftX())),
        DriveConstants.MAX_ANGULAR_VELOCITY.times(stick(-driver.getRightX())));
  }

  // Asymptotically linear: the curve eases out of zero over the width and then converges onto the
  // straight line, so there is one line for muscle memory to learn rather than a flat zone whose
  // edge the driver has to find. The hard zero is narrower than the easing and lands where the
  // curve is already worth about a fifth of a percent, so it is a step nobody can feel.
  static double stick(double axis) {
    if (Math.abs(axis) < DriveConstants.DRIVER_DEADBAND) {
      return 0;
    }
    double width = DriveConstants.DRIVER_CURVE_WIDTH;
    return (axis - width * Math.tanh(axis / width)) / (1 - width * Math.tanh(1 / width));
  }

  public void setVelocities(ChassisVelocities velocities) {
    var states = moduleTargets(velocities);
    for (int i = 0; i < MODULES; i++) {
      modules[i].setVelocity(states[i]);
    }
  }

  public void setVelocities(ChassisVelocities velocities, ChassisAccelerations accelerations) {
    var states = moduleTargets(velocities);
    // The two-argument form, always: toWheelAccelerations hardcodes omega = 0 and drops the
    // centripetal term, which dominates during exactly the manoeuvre this feedforward is for.
    var moduleAccelerations =
        kinematics.toSwerveModuleAccelerations(accelerations, velocities.omega);
    for (int i = 0; i < MODULES; i++) {
      modules[i].setVelocity(states[i], moduleAccelerations[i]);
    }
  }

  public void setOpenLoopVelocities(ChassisVelocities velocities) {
    var states = moduleTargets(velocities);
    for (int i = 0; i < MODULES; i++) {
      modules[i].setOpenLoopVelocity(states[i]);
    }
  }

  private SwerveModuleVelocity[] moduleTargets(ChassisVelocities velocities) {
    desiredVelocities = velocities;
    var target = velocities.discretize(Constants.LOOP_PERIOD.in(Seconds));
    return SwerveDriveKinematics.desaturateWheelVelocities(
        kinematics.toSwerveModuleVelocities(target),
        DriveConstants.MAX_VELOCITY.in(MetersPerSecond));
  }

  public void stopModules() {
    for (var module : modules) {
      module.stop();
    }
    desiredVelocities = new ChassisVelocities();
  }

  // followPath returns normally on a timeout rather than throwing, so a routine that awaited it
  // cannot otherwise tell a path that finished from one that gave up a metre short.
  public boolean lastPathTimedOut() {
    return pathTimedOut;
  }

  public Rotation3d getGyroHeading() {
    // Not Pigeon2.getRotation3d(): through 26.50.0-alpha-1 nothing drives the four quaternion
    // signals it reads in simulation, so it answers identity there for ever. Yaw, pitch and roll
    // are driven, and are the same three numbers.
    return new Rotation3d(
        gyro.getRoll().getValue(), gyro.getPitch().getValue(), gyro.getYaw().getValue());
  }

  public SwerveModulePosition[] getModulePositions() {
    var positions = new SwerveModulePosition[MODULES];
    for (int i = 0; i < MODULES; i++) {
      positions[i] = modules[i].getPosition();
    }
    return positions;
  }

  public SwerveDriveKinematics getKinematics() {
    return kinematics;
  }

  public ChassisVelocities getVelocities() {
    return kinematics.toChassisVelocities(measuredStates());
  }

  public void log() {
    var measured = measuredStates();
    chassisLog.log("DesiredVelocities", desiredVelocities, ChassisVelocities.struct);
    chassisLog.log(
        "MeasuredVelocities", kinematics.toChassisVelocities(measured), ChassisVelocities.struct);
    // The array form is what the visualiser consumes and the named subtables are what a human
    // reads, and a corner/index mismatch is only visible if both are written.
    moduleLog.log("DesiredStates", desiredStates(), SwerveModuleVelocity.struct);
    moduleLog.log("MeasuredStates", measured, SwerveModuleVelocity.struct);
    odometryLog.log("GyroHeading", getGyroHeading(), Rotation3d.struct);
    odometryLog.log("GyroRate", gyro.getAngularVelocityZWorld().getValue());
    for (var module : modules) {
      module.log();
    }
  }

  public void simulationInit() {
    simYaw = Radians.zero();
    lastTrueRotation = physics.truePose().getRotation();
    // setYaw's offset is integrated on top of whatever setRawYaw writes, by design, so zeroing
    // one leaves the other's offset in every reading with nothing in the log to show it.
    retry(() -> gyroSim.setRawYaw(0));
    retry(() -> gyro.setYaw(0));
  }

  public void updateSim() {
    retry(() -> gyroSim.setSupplyVoltage(physics.batteryVoltage()));

    var driveVolts = new double[MODULES];
    var steerVolts = new double[MODULES];
    var state = physics.moduleStates();
    for (int i = 0; i < MODULES; i++) {
      driveLoops[i].setSetpoint(modules[i].getDriveSetpoint());
      steerLoops[i].setSetpoint(modules[i].getSteerSetpoint());
    }

    // The setpoints are held constant across the sub-steps, which is what reproduces a 200 Hz
    // robot commanding a 1 kHz controller rather than pretending they run at the same rate.
    for (int step = 0; step < SUB_STEPS; step++) {
      for (int i = 0; i < MODULES; i++) {
        double wheelSpeed =
            state[i].wheelVelocityRadPerSec() * DriveConstants.WHEEL_RADIUS.in(Meters);
        double sensor = modules[i].toSensorRotations(state[i].azimuth());
        if (!modules[i].isClosingLoops()) {
          driveVolts[i] = 0;
          steerVolts[i] = 0;
        } else {
          // An open-loop module was written a voltage, so there is no loop here to model: the
          // number the SPARK was handed is the number the plant gets.
          driveVolts[i] =
              modules[i].isOpenLoop()
                  ? SwerveModule.openLoopVolts(modules[i].getDriveSetpoint())
                  : driveLoops[i].calculate(wheelSpeed, SUB_STEP);
          steerVolts[i] = steerLoops[i].calculate(sensor, SUB_STEP);
        }
      }
      state = physics.update(driveVolts, steerVolts, SUB_STEP);
    }

    for (int i = 0; i < MODULES; i++) {
      // setPosition takes the value after the conversion factor, so these are the metres and the
      // rotations the mechanism will read back, not raw encoder units.
      driveEncoderSims[i].setPosition(
          state[i].wheelPositionRad() * DriveConstants.WHEEL_RADIUS.in(Meters));
      driveEncoderSims[i].setVelocity(
          state[i].wheelVelocityRadPerSec() * DriveConstants.WHEEL_RADIUS.in(Meters));
      steerSensorSims[i].setPosition(modules[i].toSensorRotations(state[i].azimuth()));
    }

    RoboRioSim.setVInVoltage(physics.batteryVoltage().in(Volts));

    // The Pigeon integrates what setRawYaw is handed, so it has to be a continuous angle. A
    // Rotation2d is wrapped, and handing it one steps a whole turn at the boundary, which the
    // emulated yaw then takes as real rotation the robot never did.
    var trueRotation = physics.truePose().getRotation();
    simYaw = simYaw.plus(trueRotation.minus(lastTrueRotation).getMeasure());
    lastTrueRotation = trueRotation;
    simDrift = simDrift.plus(DriveConstants.GYRO_SIM_DRIFT.times(Constants.LOOP_PERIOD));
    retry(() -> gyroSim.setRawYaw(simYaw.plus(simDrift)));
    retry(() -> gyroSim.setAngularVelocityZ(RadiansPerSecond.of(physics.trueVelocity().omega)));

    simLog.log("TruePose", physics.truePose(), Pose2d.struct);
    boolean[] slip = new boolean[MODULES];
    for (int i = 0; i < MODULES; i++) {
      slip[i] = state[i].slipping();
    }
    simLog.log("ModuleSlip", slip);
  }

  private SwerveModuleVelocity[] desiredStates() {
    var states = new SwerveModuleVelocity[MODULES];
    for (int i = 0; i < MODULES; i++) {
      states[i] = modules[i].getDesiredVelocity();
    }
    return states;
  }

  private SwerveModuleVelocity[] measuredStates() {
    var states = new SwerveModuleVelocity[MODULES];
    for (int i = 0; i < MODULES; i++) {
      states[i] = modules[i].getVelocity();
    }
    return states;
  }

  // A simulation write is not a config write, so it never raises an alert: constructing one every
  // loop under a fixed id throws on the duplicate.
  private static void retry(Supplier<StatusCode> write) {
    for (int attempt = 1; attempt <= PHOENIX_ATTEMPTS; attempt++) {
      if (write.get().isOK()) {
        return;
      }
    }
  }
}
