// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Seconds;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.internal.UnitTelemetry;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.Measure;

class HolonomicPathFollowerTest {
  private static final double TOLERANCE = 1e-9;
  private static final double SPEED = 1.0;
  private static final double LENGTH = 2.0;

  private static final HolonomicPathFollower.Config CONFIG =
      new HolonomicPathFollower.Config(
          5.0, 5.0, 5.0, Meters.of(0.05), Degrees.of(2), Seconds.of(1));

  private MockTelemetryBackend backend;
  private TelemetryTable log;
  private Pose2d pose = Pose2d.kZero;
  private double now;

  @BeforeEach
  void setUp() {
    TelemetryRegistry.registerTypeHandler(
        Measure.class, (table, name, value) -> UnitTelemetry.log(table, name, value));
    backend = new MockTelemetryBackend();
    log = new TelemetryTable(backend);
    // The mock backend retains what it is handed and HolonomicSample's fields are public, so
    // every setpoint write warns here. The robot's backends serialise on the spot and do not.
    TelemetryRegistry.setReportWarning((path, message) -> {});
  }

  @AfterEach
  void tearDown() {
    TelemetryRegistry.setReportWarning(null);
    TelemetryRegistry.reset();
    backend.close();
  }

  // Straight along +x at a constant speed, with the robot facing whichever way the caller asks —
  // which is what lets one path test both the correction and the frame the errors are reported in.
  private static HolonomicTrajectory straightAlongX(Rotation2d heading) {
    return new HolonomicTrajectory(
        List.of(
            new HolonomicSample(
                0,
                new Pose2d(0, 0, heading),
                new ChassisVelocities(SPEED, 0, 0),
                new ChassisAccelerations()),
            new HolonomicSample(
                LENGTH / SPEED,
                new Pose2d(LENGTH, 0, heading),
                new ChassisVelocities(SPEED, 0, 0),
                new ChassisAccelerations())));
  }

  private HolonomicPathFollower follower(HolonomicTrajectory trajectory) {
    return new HolonomicPathFollower(trajectory, () -> pose, () -> now, CONFIG, log);
  }

  @Test
  void aRobotSittingOnThePathIsHandedTheSamplesOwnVelocity() {
    pose = new Pose2d(0, 0, Rotation2d.kZero);

    var velocities = follower(straightAlongX(Rotation2d.kZero)).nextFieldRelativeVelocities();

    assertEquals(SPEED, velocities.vx, TOLERANCE);
    assertEquals(0, velocities.vy, TOLERANCE);
    assertEquals(0, velocities.omega, TOLERANCE);
  }

  // Field-relative, and the assertion is that the correction points at the path in field terms
  // rather than in the robot's. A robot facing +y that is a metre to the field's left needs -y.
  @Test
  void theCorrectionIsInFieldTermsRatherThanTheRobots() {
    pose = new Pose2d(0, 0.1, Rotation2d.kCCW_Pi_2);

    var velocities = follower(straightAlongX(Rotation2d.kCCW_Pi_2)).nextFieldRelativeVelocities();

    assertEquals(SPEED, velocities.vx, TOLERANCE);
    assertEquals(-0.5, velocities.vy, TOLERANCE);
  }

  @Test
  void poseErrorIsReportedAlongAndAcrossTheTrackRatherThanInXAndY() {
    // Behind the sample in field +x, with the robot facing field +y: that is a whole rotation
    // away from being a lag, and x/y error alone cannot say so.
    pose = new Pose2d(-0.3, 0, Rotation2d.kCCW_Pi_2);

    follower(straightAlongX(Rotation2d.kCCW_Pi_2)).nextFieldRelativeVelocities();

    assertEquals(0, backend.getLastValue("/AlongTrackError", Double.class), TOLERANCE);
    assertEquals(-0.3, backend.getLastValue("/CrossTrackError", Double.class), TOLERANCE);
  }

  @Test
  void aPathWhoseClockRanOutSomewhereElseIsNotDone() {
    var follower = follower(straightAlongX(Rotation2d.kZero));
    pose = new Pose2d(0.5, 0, Rotation2d.kZero);

    now = LENGTH / SPEED + 1;

    assertFalse(follower.isFinished(), "a robot half a path short of the end reported done");

    pose = new Pose2d(LENGTH, 0, Rotation2d.kZero);
    assertTrue(follower.isFinished(), "a robot at the end of a finished path did not report done");
  }

  @Test
  void aPathThatHasNotRunItsDurationIsNotDoneEvenSittingOnTheEndPose() {
    var follower = follower(straightAlongX(Rotation2d.kZero));
    pose = new Pose2d(LENGTH, 0, Rotation2d.kZero);

    assertFalse(follower.isFinished(), "a path reported done before it had run");
  }

  @Test
  void theTimeoutIsAMarginOverTheTrajectoryDurationRatherThanAnAbsolute() {
    var follower = follower(straightAlongX(Rotation2d.kZero));

    assertEquals(
        LENGTH / SPEED + CONFIG.timeoutMargin().in(Seconds),
        follower.timeout().in(Seconds),
        TOLERANCE);
  }

  @Test
  void theAccelerationHandedOnIsTheSamplesOwnRatherThanADerivative() {
    var accelerating =
        new HolonomicTrajectory(
            List.of(
                new HolonomicSample(
                    0,
                    Pose2d.kZero,
                    new ChassisVelocities(),
                    new ChassisAccelerations(2.5, 0, 0.75)),
                new HolonomicSample(
                    1,
                    new Pose2d(1.25, 0, Rotation2d.kZero),
                    new ChassisVelocities(2.5, 0, 0.75),
                    new ChassisAccelerations(2.5, 0, 0.75))));
    var follower = follower(accelerating);

    follower.nextFieldRelativeVelocities();

    assertEquals(2.5, follower.currentAcceleration().ax, TOLERANCE);
    assertEquals(0.75, follower.currentAcceleration().alpha, TOLERANCE);
  }
}
