// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first;

import org.wpilib.framework.RobotBase;

public final class Main {
  private Main() {}

  public static void main(String... args) {
    // startRobot now takes a Supplier, not the template's Class.
    RobotBase.startRobot(first.robot.Robot::new);
  }
}
