// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Centimeters;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Seconds;

import first.robot.sim.OnboardLoopSim;
import first.robot.sim.SimModuleState;
import first.robot.sim.SwerveDriveSim;
import first.robot.sim.SwerveSimConfig;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.internal.UnitTelemetry;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.util.MathUtil;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.Measure;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;

class PoseEstimatorTest {
  private static final int MODULES = 4;
  private static final double SUB_STEP = DriveConstants.CONTROLLER_PERIOD.in(Seconds);
  private static final int SUB_STEPS =
      (int) Math.round(Constants.LOOP_PERIOD.in(Seconds) / SUB_STEP);

  private static final double DRIVE_VOLTS = 6.0;
  private static final Time SETTLE = Seconds.of(1);
  private static final Time TRAVEL = Seconds.of(2);

  // Two integrators over the same kinematics disagree only by their step: the model runs at the
  // controller period and odometry exponentiates one twist per robot loop.
  private static final Distance TRANSLATION_TOLERANCE = Centimeters.of(2);
  private static final Angle ROTATION_TOLERANCE = Degrees.of(1);

  private final SwerveSimConfig config = DriveConstants.simConfig();
  private final SwerveDriveSim sim = new SwerveDriveSim(config);
  private final SwerveDriveKinematics kinematics =
      new SwerveDriveKinematics(config.moduleLocations());
  private final double[] driveVolts = new double[MODULES];
  private final double[] steerVolts = new double[MODULES];
  private final OnboardLoopSim[] steerLoops = new OnboardLoopSim[MODULES];

  private MockTelemetryBackend backend;
  private PoseEstimator estimator;
  private SimModuleState[] state;

  @BeforeEach
  void buildEstimator() {
    // RobotBase's constructor is what registers this handler, and no Tier 1 test builds one.
    TelemetryRegistry.registerTypeHandler(
        Measure.class, (table, name, value) -> UnitTelemetry.log(table, name, value));
    backend = new MockTelemetryBackend();

    var gains = DriveConstants.SIM_GAINS.steer();
    for (int i = 0; i < MODULES; i++) {
      // The wrap range is the converted analog sensor's, which is not Rotation2d's.
      steerLoops[i] = OnboardLoopSim.position(gains.kP(), gains.kD(), gains.dFilter(), 0, 1);
    }
    state = sim.moduleStates();

    estimator =
        new PoseEstimator(
            kinematics, gyroHeading(), modulePositions(), new TelemetryTable(backend));
  }

  @AfterEach
  void clearRegistry() {
    TelemetryRegistry.reset();
    backend.close();
  }

  @Test
  void theOdometryOnlyPoseTracksTheSimulationsTruePoseThroughATranslation() {
    // An eighth of a turn off the frame's axis, so the run displaces both axes rather than one.
    steerTo(0.125);
    advance(SETTLE);
    Arrays.fill(driveVolts, DRIVE_VOLTS);
    advance(TRAVEL);

    var truePose = sim.truePose();
    assertTrue(truePose.getTranslation().getNorm() > 1, "the robot barely moved: " + truePose);
    assertTracks(truePose);
  }

  @Test
  void theOdometryOnlyPoseTracksTheSimulationsTruePoseThroughASpin() {
    var targets = kinematics.toSwerveModuleVelocities(new ChassisVelocities(0, 0, 1));
    for (int i = 0; i < MODULES; i++) {
      steerLoops[i].setSetpoint(MathUtil.inputModulus(targets[i].angle.getRotations(), 0, 1));
    }
    advance(SETTLE);
    Arrays.fill(driveVolts, DRIVE_VOLTS);
    advance(TRAVEL);

    // Asserted on the rate rather than the heading: the spin passes a full turn, and a wrapped
    // heading can read as barely moved.
    assertTrue(
        Math.abs(sim.trueVelocity().omega) > 5, "the robot is not turning: " + sim.trueVelocity());
    assertTracks(sim.truePose());
  }

  @Test
  void resettingThePoseMovesBothEstimatorsTogether() {
    var seed = new Pose2d(3, 4, Rotation2d.fromDegrees(90));

    estimator.resetPose(seed);

    assertPose(seed, estimator.getEstimatedPose(), "the fused estimate did not reset");
    assertPose(
        seed,
        estimator.getOdometryOnlyPose().toPose2d(),
        "the odometry-only estimate did not reset");
  }

  @Test
  void aVisionMeasurementMovesOnlyTheFusedEstimate() {
    advance(SETTLE);
    double captured = Timer.getMonotonicTimestamp();
    advance(SETTLE);

    estimator.visionUpdate(
        new Pose3d(new Pose2d(1, 0, Rotation2d.kZero)),
        captured,
        VecBuilder.fill(0.1, 0.1, 0.1, 0.1));

    assertEquals(
        0,
        estimator.getOdometryOnlyPose().getX(),
        1e-9,
        "the odometry-only estimate was told about vision");
    assertTrue(
        estimator.getEstimatedPose().getX() > 0.1,
        "the fused estimate ignored the measurement: " + estimator.getEstimatedPose());
  }

  private void assertTracks(Pose2d truePose) {
    var odometry = estimator.getOdometryOnlyPose().toPose2d();
    assertPose(truePose, odometry, "odometry lost the simulation");
  }

  private static void assertPose(Pose2d expected, Pose2d actual, String message) {
    assertEquals(
        expected.getX(), actual.getX(), TRANSLATION_TOLERANCE.in(Meters), message + ", in x");
    assertEquals(
        expected.getY(), actual.getY(), TRANSLATION_TOLERANCE.in(Meters), message + ", in y");
    assertEquals(
        expected.getRotation().getDegrees(),
        actual.getRotation().getDegrees(),
        ROTATION_TOLERANCE.in(Degrees),
        message + ", in heading");
  }

  private void steerTo(double sensorRotations) {
    for (var loop : steerLoops) {
      loop.setSetpoint(sensorRotations);
    }
  }

  private void advance(Time duration) {
    int ticks = (int) Math.round(duration.in(Seconds) / Constants.LOOP_PERIOD.in(Seconds));
    for (int i = 0; i < ticks; i++) {
      tick();
      estimator.odometryUpdate(gyroHeading(), modulePositions());
    }
  }

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

  // What the Pigeon reports on a flat floor: the model's own heading, widened.
  private Rotation3d gyroHeading() {
    return new Rotation3d(sim.truePose().getRotation());
  }

  private SwerveModulePosition[] modulePositions() {
    var positions = new SwerveModulePosition[MODULES];
    for (int i = 0; i < MODULES; i++) {
      positions[i] =
          new SwerveModulePosition(
              state[i].wheelPositionRad() * DriveConstants.WHEEL_RADIUS.in(Meters),
              state[i].azimuth());
    }
    return positions;
  }
}
