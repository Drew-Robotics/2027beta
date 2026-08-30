// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import java.util.ArrayList;
import java.util.List;
import org.wpilib.command3.Command;
import org.wpilib.command3.Trigger;

// Every accessor on a gamepad builds a fresh Trigger, so a binding made through one is a binding
// this opmode owns and may take back. A disable rebuilds an opmode without clearing the
// selection's scope, and a binding left behind runs a second copy of the routine on top of the
// next one.
final class Bindings {
  private final List<Trigger> bound = new ArrayList<>();

  Bindings whileHeld(Trigger button, Command command) {
    button.whileTrue(command);
    bound.add(button);
    return this;
  }

  void unbind() {
    bound.forEach(Trigger::unbind);
  }
}
