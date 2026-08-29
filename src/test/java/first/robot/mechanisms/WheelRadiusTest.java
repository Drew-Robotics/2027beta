// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Inches;
import static org.wpilib.units.Units.Meters;

import first.robot.DriveConstants;
import org.junit.jupiter.api.Test;

class WheelRadiusTest {
  private static final double TOLERANCE = 1e-12;
  private static final double DRIVE_RADIUS = DriveConstants.DRIVE_RADIUS.in(Meters);

  // Deliberately not WHEEL_RADIUS. A wheel worn down under a robot is what the measurement is for,
  // so a test built from the nominal radius would pass on arithmetic that returned its input.
  private static final double WORN = Inches.of(1.87).in(Meters);

  // A robot spun through an angle rolls each wheel through the same arc at the drive radius, so the
  // radius the arc was built from has to come back out of it.
  @Test
  void theArcRecoversTheRadiusItWasBuiltFrom() {
    double turned = 3 * 2 * Math.PI;
    double rolled = turned * DRIVE_RADIUS / WORN;

    assertEquals(WORN, Drive.effectiveWheelRadius(turned, rolled), TOLERANCE);
  }

  // The wheels roll the same distance whichever way the robot spins, and the routine hands the
  // average of four absolute deltas in, so a clockwise run must not come back negative.
  @Test
  void aClockwiseSpinMeasuresTheSameRadius() {
    double turned = -2 * Math.PI;
    double rolled = Math.abs(turned) * DRIVE_RADIUS / WORN;

    assertEquals(WORN, Drive.effectiveWheelRadius(turned, rolled), TOLERANCE);
  }

  // Zero over zero is the first loop of every run. A radius of zero there would read as a
  // measurement rather than as an answer nobody has yet.
  @Test
  void aRobotThatHasNotMovedHasNoAnswer() {
    assertTrue(
        Double.isNaN(Drive.effectiveWheelRadius(0, 0)),
        "the first loop of a run reported a wheel radius");
  }
}
