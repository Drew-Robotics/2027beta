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
    description = "Steer ramps on one module. Run supervised, on the ground")
public class SteerCharacterisationCheck implements OpMode {
  private final Bindings bindings = new Bindings();

  public SteerCharacterisationCheck(Robot robot) {
    bindings
        .onPress(robot.driver.faceUp(), robot.drive.steerQuasistatic(Direction.FORWARD))
        .onPress(robot.driver.faceDown(), robot.drive.steerQuasistatic(Direction.REVERSE))
        .onPress(robot.driver.faceLeft(), robot.drive.steerDynamic(Direction.FORWARD))
        .onPress(robot.driver.faceRight(), robot.drive.steerDynamic(Direction.REVERSE));
  }

  @Override
  public void close() {
    bindings.unbind();
  }
}
