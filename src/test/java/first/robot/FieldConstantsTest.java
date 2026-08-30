// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.util.AlertDataJNI;

class FieldConstantsTest {
  private static final double TOLERANCE = 1e-9;
  private static final Rotation2d ANGLE = Rotation2d.fromDegrees(30);

  private static final HolonomicTrajectory PATH =
      new HolonomicTrajectory(
          List.of(
              new HolonomicSample(
                  0,
                  new Pose2d(2, 3, Rotation2d.fromDegrees(30)),
                  new ChassisVelocities(1, 2, 0.5),
                  new ChassisAccelerations(3, 4, 0.75)),
              new HolonomicSample(
                  1,
                  new Pose2d(4, 5, Rotation2d.fromDegrees(30)),
                  new ChassisVelocities(1, 2, 0.5),
                  new ChassisAccelerations(3, 4, 0.75))));

  // The toggle is process-wide, so a test that leaves it armed mirrors every path in every test
  // that runs after it.
  @AfterEach
  void disarmTheMirror() {
    FieldConstants.MIRRORED.set(false);
  }

  // The origin is field centre, so the flip is a rotation about it: the translation inverts, the
  // heading turns half a turn, and the two spins keep their sign because a rotation does not
  // mirror. A reflection — which a corner origin would need — inverts omega instead.
  @Test
  void theFlipIsARotationAboutTheOriginRatherThanAReflection() {
    var flipped = FieldConstants.flip(PATH).start();

    assertEquals(-2, flipped.pose.getX(), TOLERANCE);
    assertEquals(-3, flipped.pose.getY(), TOLERANCE);
    assertEquals(-150, flipped.pose.getRotation().getDegrees(), TOLERANCE);
    assertEquals(-1, flipped.velocity.vx, TOLERANCE);
    assertEquals(-2, flipped.velocity.vy, TOLERANCE);
    assertEquals(0.5, flipped.velocity.omega, TOLERANCE);
    assertEquals(-3, flipped.acceleration.ax, TOLERANCE);
    assertEquals(-4, flipped.acceleration.ay, TOLERANCE);
    assertEquals(0.75, flipped.acceleration.alpha, TOLERANCE);
  }

  // The centre origin is what makes the flip a rotation, so the two conversions have to put the
  // field's own edges symmetrically about zero. A wrong dimension shows up here and nowhere else
  // until a path is flipped off the field.
  @Test
  void theCornerConversionPutsTheFieldSymmetricallyAboutTheOrigin() {
    assertEquals(-FieldConstants.fromCornerX(16.541), FieldConstants.fromCornerX(0), TOLERANCE);
    assertEquals(-FieldConstants.fromCornerY(8.0692), FieldConstants.fromCornerY(0), TOLERANCE);
  }

  // A corner-frame pose and its rotation about the field's centre both land in the converted
  // frame as a pair the flip carries between, which is the whole reason the conversion exists.
  @Test
  void aCornerFramePoseFlipsToTheRotationOfItselfAboutTheFieldCentre() {
    var drawn = new Pose2d(FieldConstants.fromCornerX(2), FieldConstants.fromCornerY(2), ANGLE);

    var flipped = FieldConstants.flip(drawn);

    assertEquals(FieldConstants.fromCornerX(16.541 - 2), flipped.getX(), TOLERANCE);
    assertEquals(FieldConstants.fromCornerY(8.0692 - 2), flipped.getY(), TOLERANCE);
  }

  @Test
  void flippingTwiceIsTheOriginalPath() {
    var round = FieldConstants.flip(FieldConstants.flip(PATH)).start();

    assertEquals(2, round.pose.getX(), TOLERANCE);
    assertEquals(3, round.pose.getY(), TOLERANCE);
    assertEquals(30, round.pose.getRotation().getDegrees(), TOLERANCE);
  }

  // No Driver Station is attached under a unit test, which is the same case as a robot on a bench
  // with nobody plugged in.
  @Test
  void aMissingAllianceRaisesAnAlertRatherThanFlippingOnAGuess() {
    assertSame(
        PATH, FieldConstants.forAlliance(PATH), "a path was flipped with no alliance to go on");

    assertTrue(
        Arrays.stream(AlertDataJNI.getAlerts())
            .anyMatch(
                alert ->
                    alert.id.equals("path-alliance-unknown")
                        && alert.activeStartTime != 0
                        && alert.level == AlertDataJNI.LEVEL_HIGH),
        "no high-level alert was raised for a path followed without an alliance");
  }

  // The mirror is a reflection and the flip is a rotation, so the mirror negates the two spins the
  // flip deliberately leaves alone. A mirror that copies the flip's signs tracks translation
  // perfectly and turns the wrong way, which reads as a tuning problem rather than a sign error.
  @Test
  void theMirrorIsAReflectionAboutTheLongAxisRatherThanARotation() {
    var mirrored = FieldConstants.mirror(PATH).start();

    assertEquals(2, mirrored.pose.getX(), TOLERANCE);
    assertEquals(-3, mirrored.pose.getY(), TOLERANCE);
    assertEquals(-30, mirrored.pose.getRotation().getDegrees(), TOLERANCE);
    assertEquals(1, mirrored.velocity.vx, TOLERANCE);
    assertEquals(-2, mirrored.velocity.vy, TOLERANCE);
    assertEquals(-0.5, mirrored.velocity.omega, TOLERANCE);
    assertEquals(3, mirrored.acceleration.ax, TOLERANCE);
    assertEquals(-4, mirrored.acceleration.ay, TOLERANCE);
    assertEquals(-0.75, mirrored.acceleration.alpha, TOLERANCE);
  }

  // Both transforms are applied at the same instant and nothing sequences them, so the order has
  // to stay something nobody has to know.
  @Test
  void theMirrorAndTheAllianceFlipCommute() {
    var mirrorThenFlip = FieldConstants.flip(FieldConstants.mirror(PATH)).start();
    var flipThenMirror = FieldConstants.mirror(FieldConstants.flip(PATH)).start();

    assertEquals(mirrorThenFlip.pose.getX(), flipThenMirror.pose.getX(), TOLERANCE);
    assertEquals(mirrorThenFlip.pose.getY(), flipThenMirror.pose.getY(), TOLERANCE);
    assertEquals(
        mirrorThenFlip.pose.getRotation().getDegrees(),
        flipThenMirror.pose.getRotation().getDegrees(),
        TOLERANCE);
    assertEquals(mirrorThenFlip.velocity.vx, flipThenMirror.velocity.vx, TOLERANCE);
    assertEquals(mirrorThenFlip.velocity.vy, flipThenMirror.velocity.vy, TOLERANCE);
    assertEquals(mirrorThenFlip.velocity.omega, flipThenMirror.velocity.omega, TOLERANCE);
    assertEquals(mirrorThenFlip.acceleration.ax, flipThenMirror.acceleration.ax, TOLERANCE);
    assertEquals(mirrorThenFlip.acceleration.ay, flipThenMirror.acceleration.ay, TOLERANCE);
    assertEquals(mirrorThenFlip.acceleration.alpha, flipThenMirror.acceleration.alpha, TOLERANCE);
  }

  @Test
  void mirroringTwiceIsTheOriginalPath() {
    var round = FieldConstants.mirror(FieldConstants.mirror(PATH)).start();

    assertEquals(2, round.pose.getX(), TOLERANCE);
    assertEquals(3, round.pose.getY(), TOLERANCE);
    assertEquals(30, round.pose.getRotation().getDegrees(), TOLERANCE);
    assertEquals(0.5, round.velocity.omega, TOLERANCE);
  }

  // The path as drawn is what a robot boots into, so a mirror left on from practice cannot be the
  // state nobody set.
  @Test
  void aPathIsFollowedAsAuthoredUntilTheDashboardSaysOtherwise() {
    assertSame(
        PATH, FieldConstants.forSide(PATH), "a path was mirrored with nothing asking for it");
  }

  // The same threshold problem the flip has: a trigger written against the drawn path is compared
  // against a mirrored estimate and never fires, so one function has to undo both transforms.
  @Test
  void asAuthoredUndoesTheMirrorAsWellAsTheFlip() {
    FieldConstants.MIRRORED.set(true);
    var drawn = PATH.start().pose;

    var driven = FieldConstants.forSide(PATH).start().pose;
    var back = FieldConstants.asAuthored(driven);

    assertEquals(-3, driven.getY(), TOLERANCE);
    assertEquals(drawn.getX(), back.getX(), TOLERANCE);
    assertEquals(drawn.getY(), back.getY(), TOLERANCE);
    assertEquals(drawn.getRotation().getDegrees(), back.getRotation().getDegrees(), TOLERANCE);
  }

  // What asAuthored leans on: it has to undo exactly what the trajectory flip did, or a threshold
  // written against the drawn path is compared against the wrong half of the field on red — and a
  // trigger that never fires is quieter than one that fires in the wrong place.
  @Test
  void thePoseFlipUndoesWhatTheTrajectoryFlipDidToTheSamePose() {
    var drawn = PATH.start().pose;

    var driven = FieldConstants.flip(PATH).start().pose;
    var back = FieldConstants.flip(driven);

    assertEquals(-2, driven.getX(), TOLERANCE);
    assertEquals(-3, driven.getY(), TOLERANCE);
    assertEquals(drawn.getX(), back.getX(), TOLERANCE);
    assertEquals(drawn.getY(), back.getY(), TOLERANCE);
    assertEquals(drawn.getRotation().getDegrees(), back.getRotation().getDegrees(), TOLERANCE);
  }
}
