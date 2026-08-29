// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.Robot;
import first.robot.sysid.SysIdRoutine.Direction;
import java.util.ArrayList;
import java.util.List;
import org.wpilib.command3.Command;
import org.wpilib.command3.Trigger;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Utility;

// Supervised, on the ground, with room ahead of the robot. A feedforward gain measured with the
// wheels off the carpet is a measurement of a motor and not of a drive base, so this is the one
// utility opmode that does not run on blocks.
@Utility(
    group = "Characterisation",
    description = "SysId drive and steer ramps; run supervised, on the ground")
public class CharacterisationCheck implements OpMode {
  private final List<Trigger> bound = new ArrayList<>();

  public CharacterisationCheck(Robot robot) {
    bind(robot.driver.faceUp(), robot.drive.driveQuasistatic(Direction.FORWARD));
    bind(robot.driver.faceDown(), robot.drive.driveQuasistatic(Direction.REVERSE));
    bind(robot.driver.faceLeft(), robot.drive.driveDynamic(Direction.FORWARD));
    bind(robot.driver.faceRight(), robot.drive.driveDynamic(Direction.REVERSE));
    // Steer takes no dynamic test: its feedforward is kS alone, and a step voltage would fit a
    // gain the position loop cannot apply.
    bind(robot.driver.leftBumper(), robot.drive.steerQuasistatic(Direction.FORWARD));
    bind(robot.driver.rightBumper(), robot.drive.steerQuasistatic(Direction.REVERSE));
  }

  // Each accessor on the gamepad builds a fresh Trigger, so every one of these is a trigger this
  // opmode constructed and may unbind. A disable rebuilds the opmode without clearing the
  // selection's scope, and a binding left behind runs a second ramp on top of the next one.
  @Override
  public void close() {
    bound.forEach(Trigger::unbind);
  }

  private void bind(Trigger button, Command routine) {
    button.onTrue(routine);
    bound.add(button);
  }
}
