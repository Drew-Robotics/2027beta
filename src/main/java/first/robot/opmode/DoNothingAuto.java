// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.Robot;
import org.wpilib.opmode.Autonomous;
import org.wpilib.opmode.OpMode;

// Binds nothing, so every mechanism runs the safe default command Robot set. The point is that the
// operator always has something to select when the path is wrong or the field is not where the
// robot thinks.
@Autonomous(group = "Competition", description = "Sits still")
public class DoNothingAuto implements OpMode {
  public DoNothingAuto(Robot robot) {}
}
