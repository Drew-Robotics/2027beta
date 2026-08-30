// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.Robot;
import first.robot.sysid.SysIdRoutine.Direction;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Utility;

@Utility(
    group = "Characterisation",
    description =
        "Whole-robot rotation ramps, spinning on the spot. Hold to run, supervised, on the ground")
public class RotationCharacterisationCheck implements OpMode {
  private final Bindings bindings = new Bindings();

  public RotationCharacterisationCheck(Robot robot) {
    bindings
        .whileHeld(robot.driver.faceUp(), robot.drive.rotationQuasistatic(Direction.FORWARD))
        .whileHeld(robot.driver.faceDown(), robot.drive.rotationQuasistatic(Direction.REVERSE))
        .whileHeld(robot.driver.faceLeft(), robot.drive.rotationDynamic(Direction.FORWARD))
        .whileHeld(robot.driver.faceRight(), robot.drive.rotationDynamic(Direction.REVERSE));
  }

  @Override
  public void close() {
    bindings.unbind();
  }
}
