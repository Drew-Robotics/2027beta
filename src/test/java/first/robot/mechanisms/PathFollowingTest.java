// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Seconds;

import first.robot.Constants;
import first.robot.DriveConstants;
import first.robot.HolonomicPathFollower;
import first.robot.TrajectoryLoader;
import first.robot.sim.OnboardLoopSim;
import first.robot.sim.SwerveDriveSim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.internal.UnitTelemetry;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.math.util.MathUtil;
import org.wpilib.system.Filesystem;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.Measure;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;

// The whole autonomous loop, with no HAL and no vendor type in it: follower to field-relative
// velocities to kinematics to module voltages to the plant to a pose and back into the follower.
// The cross-track number asserted here is the one the field diagnostic reports, so a regression
// looks the same in CI as it does on a field.
class PathFollowingTest {
  private static final int MODULES = 4;
  private static final double LOOP = Constants.LOOP_PERIOD.in(Seconds);
  private static final double SUB_STEP = DriveConstants.CONTROLLER_PERIOD.in(Seconds);
  private static final int SUB_STEPS = (int) Math.round(LOOP / SUB_STEP);

  private final SwerveDriveKinematics kinematics =
      new SwerveDriveKinematics(DriveConstants.simConfig().moduleLocations());
  private final SwerveDriveSim physics = new SwerveDriveSim(DriveConstants.simConfig());
  private final OnboardLoopSim[] driveLoops = new OnboardLoopSim[MODULES];
  private final OnboardLoopSim[] steerLoops = new OnboardLoopSim[MODULES];

  private MockTelemetryBackend backend;
  private TelemetryTable log;
  private double now;

  @BeforeEach
  void setUp() {
    var gains = DriveConstants.SIM_GAINS;
    for (int i = 0; i < MODULES; i++) {
      driveLoops[i] =
          OnboardLoopSim.velocity(gains.drive().kP(), gains.drive().kS(), gains.drive().kV());
      steerLoops[i] =
          OnboardLoopSim.position(
              gains.steer().kP(), gains.steer().kD(), gains.steer().dFilter(), 0, 1);
    }

    TelemetryRegistry.registerTypeHandler(
        Measure.class, (table, name, value) -> UnitTelemetry.log(table, name, value));
    backend = new MockTelemetryBackend();
    log = new TelemetryTable(backend);
    // The mock backend retains what it is handed and HolonomicSample's fields are public, so every
    // setpoint write warns here. The robot's backends serialise on the spot and do not.
    TelemetryRegistry.setReportWarning((path, message) -> {});
  }

  @AfterEach
  void tearDown() {
    TelemetryRegistry.setReportWarning(null);
    TelemetryRegistry.reset();
    backend.close();
  }

  // Straight and square to the field, so every metre of error is a metre of lag and the cross-track
  // number measures only what it claims to.
  @Test
  void aStraightPathIsDrivenWithinAWheelsWidthOfItself() {
    drive("StraightAhead", Meters.of(0.05), Seconds.of(1.5));
  }

  // Heading and translation change together, which is where a robot-relative follower and a dropped
  // centripetal term both show up. The threshold is loose because the drive lags its velocity
  // setpoint by a plant time constant with no acceleration feedforward to cancel it, and on a curve
  // that lag is read as cross-track. It tightens when characterisation produces a kA.
  @Test
  void aPathThatTurnsWhileItTranslatesIsDrivenTheSameWay() {
    drive("SweepLeft", Meters.of(0.8), Seconds.of(2));
  }

  private void drive(String pathName, Distance maxCrossTrack, Time margin) {
    var trajectory = load(pathName);
    physics.resetPose(trajectory.start().pose);

    var follower =
        new HolonomicPathFollower(
            trajectory, physics::truePose, () -> now, DriveConstants.PATH_FOLLOWER, log);

    double worstCrossTrack = 0;
    double deadline = trajectory.duration + margin.in(Seconds);
    while (!follower.isDone() && now < deadline) {
      step(follower);
      now += LOOP;
      worstCrossTrack =
          Math.max(
              worstCrossTrack, Math.abs(backend.getLastValue("/CrossTrackError", Double.class)));
    }

    assertTrue(
        follower.isDone(),
        pathName + " was still not done " + now + " s in, against a deadline of " + deadline);
    assertTrue(
        worstCrossTrack <= maxCrossTrack.in(Meters),
        pathName + " ran " + worstCrossTrack + " m off the path at its worst");
  }

  // Everything Drive does between a follower and the plant, with the SPARK-side loops modelled at
  // their own rate rather than at the robot's.
  private void step(HolonomicPathFollower follower) {
    var heading = physics.truePose().getRotation();
    var field = follower.next();
    var robotRelative = field.toRobotRelative(heading);
    var accelerations = follower.acceleration().toRobotRelative(heading);

    var targets =
        SwerveDriveKinematics.desaturateWheelVelocities(
            kinematics.toSwerveModuleVelocities(robotRelative.discretize(LOOP)),
            DriveConstants.MAX_VELOCITY.in(MetersPerSecond));
    // The two-argument form: the one-argument overload hardcodes omega = 0 and drops the
    // centripetal term, which is most of the acceleration on the path that turns.
    var moduleAccelerations =
        kinematics.toSwerveModuleAccelerations(accelerations, robotRelative.omega);

    var state = physics.moduleStates();
    var desired = new SwerveModuleVelocity[MODULES];
    var feedforward = new double[MODULES];
    for (int i = 0; i < MODULES; i++) {
      var azimuth = state[i].azimuth();
      desired[i] = targets[i].optimize(azimuth).cosineScale(azimuth);
      feedforward[i] =
          DriveConstants.DRIVE_KA
              * SwerveModule.accelerationAlong(moduleAccelerations[i], desired[i].angle);
      driveLoops[i].setSetpoint(desired[i].velocity);
      steerLoops[i].setSetpoint(MathUtil.inputModulus(desired[i].angle.getRotations(), 0, 1));
    }

    var driveVolts = new double[MODULES];
    var steerVolts = new double[MODULES];
    for (int substep = 0; substep < SUB_STEPS; substep++) {
      for (int i = 0; i < MODULES; i++) {
        double wheelSpeed =
            state[i].wheelVelocityRadPerSec() * DriveConstants.WHEEL_RADIUS.in(Meters);
        double sensor = MathUtil.inputModulus(state[i].azimuth().getRotations(), 0, 1);
        driveVolts[i] = driveLoops[i].calculate(wheelSpeed, SUB_STEP) + feedforward[i];
        steerVolts[i] = steerLoops[i].calculate(sensor, SUB_STEP);
      }
      state = physics.update(driveVolts, steerVolts, SUB_STEP);
    }
  }

  private static HolonomicTrajectory load(String pathName) {
    return new TrajectoryLoader(Filesystem.getDeployDirectory().toPath()).get(pathName);
  }
}
