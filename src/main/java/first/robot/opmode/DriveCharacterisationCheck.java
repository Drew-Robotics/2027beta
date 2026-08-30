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
        "Straight-line module ramps, wheels locked forward. Run supervised, on the ground")
public class DriveCharacterisationCheck implements OpMode {
  private final Bindings bindings = new Bindings();

  public DriveCharacterisationCheck(Robot robot) {
    bindings
        .onPress(robot.driver.faceUp(), robot.drive.driveQuasistatic(Direction.FORWARD))
        .onPress(robot.driver.faceDown(), robot.drive.driveQuasistatic(Direction.REVERSE))
        .onPress(robot.driver.faceLeft(), robot.drive.driveDynamic(Direction.FORWARD))
        .onPress(robot.driver.faceRight(), robot.drive.driveDynamic(Direction.REVERSE));
  }

  @Override
  public void close() {
    bindings.unbind();
  }
}
