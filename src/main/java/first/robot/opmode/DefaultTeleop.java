// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.Robot;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Teleop;

@Teleop
public class DefaultTeleop implements OpMode {
  public DefaultTeleop(Robot robot) {
    robot.drive.setDefaultCommand(robot.drive.driverControl(robot.driver));

    // Field-relative has no answer for a heading the driver has stopped believing. Holding the
    // bumper drives the chassis frame directly; releasing it goes back.
    robot.driver.leftBumper().whileTrue(robot.drive.driverControlRobotRelative(robot.driver));
  }
}
