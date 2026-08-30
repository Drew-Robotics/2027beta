// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Radians;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.RadiansPerSecondPerSecond;
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
import first.robot.DriveConstants.ModuleGains;
import first.robot.DriveConstants.SwerveModuleConfig;
import first.robot.Hardware;
import first.robot.HolonomicPathFollower;
import first.robot.sim.OnboardLoopSim;
import first.robot.sim.SwerveDriveSim;
import first.robot.sysid.SysIdRoutine;
import first.robot.sysid.SysIdRoutine.Direction;
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
import org.wpilib.math.filter.SlewRateLimiter;
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
import org.wpilib.sysid.SysIdRoutineLog;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.Voltage;

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
  private final TelemetryTable characterisationLog;
  private final TelemetryTable autoLog;
  private final TelemetryTable simLog;
  private final Scheduler scheduler;
  private final SysIdRoutine driveCharacterisation;
  private final SysIdRoutine steerCharacterisation;
  private final SysIdRoutine rotationCharacterisation;
  private final Rotation2d[] spinAzimuths = new Rotation2d[MODULES];

  private ChassisVelocities desiredVelocities = new ChassisVelocities();
  private ModuleGains gains;
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
    characterisationLog = log.getTable("Characterisation");

    gains = DriveConstants.gains();
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

    // The azimuths of a pure spin, which are fixed by the module locations. Taken from the
    // kinematics rather than written out as four angles, so they cannot disagree with it.
    var spin = kinematics.toSwerveModuleVelocities(new ChassisVelocities(0, 0, 1));
    for (int i = 0; i < MODULES; i++) {
      spinAzimuths[i] = spin[i].angle;
    }

    // Three routines and three log names: SysIdRoutineLog names every entry after the routine's,
    // so one name shared would append three mechanisms' data into one set of columns.
    driveCharacterisation =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.DRIVE_RAMP_RATE,
                DriveConstants.DRIVE_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(this::characteriseDrive, this::logDriveRamp, this, "drive"));
    steerCharacterisation =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.STEER_RAMP_RATE,
                DriveConstants.STEER_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(this::characteriseSteer, this::logSteerRamp, this, "steer"));
    rotationCharacterisation =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.ROTATION_RAMP_RATE,
                DriveConstants.ROTATION_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(
                this::characteriseRotation, this::logRotationRamp, this, "rotation"));

    if (RobotBase.isSimulation()) {
      physics = new SwerveDriveSim(DriveConstants.simConfig());
      gyroSim = gyro.getSimState();
      simDrift = Radians.zero();
      simYaw = Radians.zero();
      lastTrueRotation = physics.truePose().getRotation();
      buildLoops(gains);
      for (int i = 0; i < MODULES; i++) {
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

  private void buildLoops(ModuleGains moduleGains) {
    for (int i = 0; i < MODULES; i++) {
      driveLoops[i] =
          OnboardLoopSim.velocity(
              moduleGains.drive().kP(), moduleGains.drive().kS(), moduleGains.drive().kV());
      steerLoops[i] =
          OnboardLoopSim.position(
              moduleGains.steer().kP(),
              moduleGains.steer().kD(),
              moduleGains.steer().dFilter(),
              0,
              1);
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

  // The three routines below each run all four tests, quasistatic and dynamic, forward and
  // reverse. The analyser combines all four into one dataset and fits one model over it, so the
  // four are the measurement rather than four separate ones — a routine that skipped a pair
  // produces a log it will not open. That holds for steer too, whose dynamic pair helps fit the
  // kS it keeps even though the kV and kA beside it have nowhere to go on a position loop.
  //
  // Each settles its modules into the azimuth the ramp pushes along before handing over to the
  // carried routine, whose own command keeps its upstream name — "sysid-quasistatic-forward-drive"
  // and its siblings — because the analyser's readers already know them.
  public Command driveQuasistatic(Direction direction) {
    return settledFirst(
        this::pointForward,
        driveCharacterisation.quasistatic(direction),
        "Drive.DriveQuasistatic[" + direction + "]");
  }

  public Command driveDynamic(Direction direction) {
    return settledFirst(
        this::pointForward,
        driveCharacterisation.dynamic(direction),
        "Drive.DriveDynamic[" + direction + "]");
  }

  public Command rotationQuasistatic(Direction direction) {
    return settledFirst(
        this::pointAroundTheSpin,
        rotationCharacterisation.quasistatic(direction),
        "Drive.RotationQuasistatic[" + direction + "]");
  }

  public Command rotationDynamic(Direction direction) {
    return settledFirst(
        this::pointAroundTheSpin,
        rotationCharacterisation.dynamic(direction),
        "Drive.RotationDynamic[" + direction + "]");
  }

  // No settle, and none to make: the module starts wherever it is parked, and where it is parked
  // is not a property the measurement depends on.
  public Command steerQuasistatic(Direction direction) {
    return steerCharacterisation.quasistatic(direction);
  }

  public Command steerDynamic(Direction direction) {
    return steerCharacterisation.dynamic(direction);
  }

  private Command settledFirst(Runnable point, Command routine, String name) {
    return Command.noRequirements(
            coroutine -> {
              coroutine.await(settle(point));
              coroutine.await(routine);
            })
        .named(name);
  }

  // A ramp measures the drive motor, so the modules have to be pointing where it pushes before it
  // starts. From a module parked across the robot, the first fraction of a second of the ramp
  // would otherwise be logged while the wheel is being turned under itself.
  private Command settle(Runnable point) {
    return runRepeatedly(point)
        .named("Drive.Settle")
        .withTimeout(DriveConstants.CHARACTERISATION_SETTLE);
  }

  private void pointForward() {
    characteriseDrive(Volts.zero());
  }

  private void pointAroundTheSpin() {
    characteriseRotation(Volts.zero());
  }

  private void characteriseDrive(Voltage volts) {
    for (var module : modules) {
      module.characteriseDrive(Rotation2d.kZero, volts);
    }
  }

  private void characteriseRotation(Voltage volts) {
    for (int i = 0; i < MODULES; i++) {
      modules[i].characteriseDrive(spinAzimuths[i], volts);
    }
  }

  // One module turns and the other three are dropped. Steer is a module-level measurement — the
  // module is what the gain is for — and four modules fighting the carpet at once is three extra
  // ways for the one being measured to be pushed.
  private void characteriseSteer(Voltage volts) {
    for (int i = 0; i < MODULES; i++) {
      if (i == DriveConstants.CHARACTERISED_MODULE) {
        modules[i].characteriseSteer(volts);
      } else {
        modules[i].stop();
      }
    }
  }

  private void logDriveRamp(SysIdRoutineLog log) {
    var module = modules[DriveConstants.CHARACTERISED_MODULE];
    log.motor(module.getName())
        .voltage(Volts.of(module.getDriveVolts()))
        .linearPosition(module.getDriveDistance())
        .linearVelocity(module.getDriveSpeed());
  }

  private void logSteerRamp(SysIdRoutineLog log) {
    var module = modules[DriveConstants.CHARACTERISED_MODULE];
    log.motor(module.getName())
        .voltage(Volts.of(module.getSteerVolts()))
        .angularPosition(module.getSteerRotation())
        .angularVelocity(module.getSteerRate());
  }

  // The robot's rotation, not a wheel's. What the fit is for is turning the robot to an angle: kS
  // is the smallest omega command that breaks the robot away at all, and kV and kA give the
  // largest a profile may ask for — none of which a module-level test can produce. The Pigeon's
  // yaw accumulates past a turn, so the position column is continuous where a Rotation2d wraps.
  private void logRotationRamp(SysIdRoutineLog log) {
    log.motor(modules[DriveConstants.CHARACTERISED_MODULE].getName())
        .voltage(Volts.of(modules[DriveConstants.CHARACTERISED_MODULE].getDriveVolts()))
        .angularPosition(gyro.getYaw().getValue())
        .angularVelocity(gyro.getAngularVelocityZWorld().getValue());
  }

  // Forward is counter-clockwise, the sign every omega in this project carries.
  public Command measureWheelRadius(Direction direction) {
    return run(coroutine -> {
          var omega =
              new SlewRateLimiter(DriveConstants.WHEEL_RADIUS_RAMP.in(RadiansPerSecondPerSecond));
          double target =
              DriveConstants.WHEEL_RADIUS_OMEGA.in(RadiansPerSecond)
                  * (direction == Direction.FORWARD ? 1 : -1);
          // A radius left over from the previous run is indistinguishable from this one's until
          // this one has turned far enough to have an answer.
          characterisationLog.log("WheelRadiusComplete", false);

          // Spun up before anything is counted. The modules slew to the spin azimuths while the
          // wheels are already being driven, and roll measured against yaw the robot has not
          // turned yet biases the radius small.
          var settling = Timer.createStarted();
          while (!settling.hasElapsed(DriveConstants.CHARACTERISATION_SETTLE)) {
            setVelocities(new ChassisVelocities(0, 0, omega.calculate(target)));
            coroutine.yield();
          }

          var startYaw = gyro.getYaw().getValue();
          var startTravel = wheelTravel();

          while (true) {
            setVelocities(new ChassisVelocities(0, 0, omega.calculate(target)));

            var turned = gyro.getYaw().getValue().minus(startYaw);
            double rolled = averageRoll(startTravel);
            characterisationLog.log("WheelRadiusYaw", turned);
            characterisationLog.log("WheelRadiusRoll", Radians.of(rolled));
            characterisationLog.log(
                "EffectiveWheelRadius",
                Meters.of(effectiveWheelRadius(turned.in(Radians), rolled)));
            characterisationLog.log(
                "WheelRadiusComplete",
                Math.abs(turned.in(Radians))
                    >= DriveConstants.WHEEL_RADIUS_MIN_ROTATION.in(Radians));
            coroutine.yield();
          }
        })
        .whenCanceled(this::stopModules)
        .named("Drive.MeasureWheelRadius[" + direction + "]");
  }

  // Each wheel rolled through an arc, and the robot turned through an arc at the drive radius. The
  // two are the same arc, so the radius that makes them agree is the one the wheels have. It is
  // deliberately not WHEEL_RADIUS: the encoder's own metres already carry the assumed radius, so
  // dividing it back out is what leaves a measurement rather than a restatement.
  static double effectiveWheelRadius(double turnedRadians, double rolledRadians) {
    if (rolledRadians <= 0) {
      return Double.NaN;
    }
    return Math.abs(turnedRadians) * DriveConstants.DRIVE_RADIUS.in(Meters) / rolledRadians;
  }

  private double[] wheelTravel() {
    var travel = new double[MODULES];
    for (int i = 0; i < MODULES; i++) {
      travel[i] = modules[i].getDriveDistance().in(Meters) / DriveConstants.WHEEL_RADIUS.in(Meters);
    }
    return travel;
  }

  // Absolute, per module, because a module that ran backwards rolled just as far. Averaged over
  // four, because one module's slip is a quarter of the error rather than all of it.
  private double averageRoll(double[] from) {
    var now = wheelTravel();
    double total = 0;
    for (int i = 0; i < MODULES; i++) {
      total += Math.abs(now[i] - from[i]);
    }
    return total / MODULES;
  }

  // The winner of a bench session, applied to every module of that role. Nothing here persists it,
  // so it lasts until the next power cycle and no longer.
  public void applyGains(ModuleGains newGains) {
    gains = newGains;
    for (var module : modules) {
      module.applyGains(newGains);
    }
    if (physics != null) {
      buildLoops(newGains);
    }
  }

  // What the controllers hold now, not what was compiled in: a tuning opmode rebuilt by a disable
  // seeded from the constants would show the deployed gain beside a controller holding a tuned one.
  public ModuleGains getGains() {
    return gains;
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
          // An axis written a voltage — open-loop teleop, or a characterisation ramp — has no
          // loop here to model: the number the SPARK was handed is the number the plant gets.
          driveVolts[i] =
              modules[i].isDriveVoltageMode()
                  ? modules[i].getDriveVolts()
                  : driveLoops[i].calculate(wheelSpeed, SUB_STEP);
          steerVolts[i] =
              modules[i].isSteerVoltageMode()
                  ? modules[i].getSteerVolts()
                  : steerLoops[i].calculate(sensor, SUB_STEP);
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
