// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.Robot;
import first.robot.sysid.SysIdRoutine.Direction;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Utility;

// Held rather than pressed: the estimate is only worth reading after a full turn of the robot, and
// how long that takes is a property of the carpet. The operator watches the number settle and
// lets go.
@Utility(
    group = "Characterisation",
    description = "Spins the robot to measure the wheel radius. Hold until the estimate settles")
public class WheelRadiusCheck implements OpMode {
  private final Bindings bindings = new Bindings();

  public WheelRadiusCheck(Robot robot) {
    bindings
        .whileHeld(robot.driver.leftBumper(), robot.drive.measureWheelRadius(Direction.FORWARD))
        .whileHeld(robot.driver.rightBumper(), robot.drive.measureWheelRadius(Direction.REVERSE));
  }

  @Override
  public void close() {
    bindings.unbind();
  }
}
