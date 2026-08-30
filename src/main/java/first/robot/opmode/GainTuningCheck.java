// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import first.robot.DriveConstants.DriveMotorGains;
import first.robot.DriveConstants.ModuleGains;
import first.robot.DriveConstants.SteerMotorGains;
import first.robot.Robot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Utility;
import org.wpilib.tunable.TunableRegistry;
import org.wpilib.tunable.Tunables;

@Utility(
    group = "Tuning",
    description = "Module gains, live. A tuned gain dies at the next power cycle")
public class GainTuningCheck implements OpMode {
  private static final String ROOT = "Tuning/Module/";

  private final Robot robot;
  private final List<String> published = new ArrayList<>();

  private boolean applyQueued;

  private double driveP;
  private double driveS;
  private double driveV;
  private double steerP;
  private double steerD;
  private double steerS;
  private double steerDFilter;

  public GainTuningCheck(Robot robot) {
    this.robot = robot;

    // Seeded from the controllers rather than from the constants, so a disable — which rebuilds
    // this opmode — does not put the compiled gain on the dashboard beside a tuned controller.
    var gains = robot.drive.getGains();
    driveP = gains.drive().kP();
    driveS = gains.drive().kS();
    driveV = gains.drive().kV();
    steerP = gains.steer().kP();
    steerD = gains.steer().kD();
    steerS = gains.steer().kS();
    steerDFilter = gains.steer().dFilter();

    publish("DriveKP", () -> driveP, value -> driveP = value);
    publish("DriveKS", () -> driveS, value -> driveS = value);
    publish("DriveKV", () -> driveV, value -> driveV = value);
    publish("SteerKP", () -> steerP, value -> steerP = value);
    publish("SteerKD", () -> steerD, value -> steerD = value);
    publish("SteerKS", () -> steerS, value -> steerS = value);
    publish("SteerDFilter", () -> steerDFilter, value -> steerDFilter = value);
  }

  // The tuned gains stay on the controllers: they die at the next power cycle, which is the whole
  // mechanism, and undoing them here would make the winner of a session vanish on a disable.
  @Override
  public void close() {
    published.forEach(Tunables::remove);
  }

  private void publish(String name, DoubleSupplier getter, DoubleConsumer setter) {
    published.add(ROOT + name);
    Tunables.publishDouble(
        ROOT + name,
        getter,
        value -> {
          setter.accept(value);
          queueApply();
        });
  }

  // One write of all seven gains reaches seven setters in a single update, and applying on each
  // would send eight blocking configures per setter inside one 5 ms period. The registry's
  // after-update hook is where a reaction to tuned values belongs; the flag is what stops seven
  // callbacks queueing behind one another.
  private void queueApply() {
    if (applyQueued) {
      return;
    }
    applyQueued = true;
    TunableRegistry.runAfterUpdate(
        () -> {
          applyQueued = false;
          robot.drive.applyGains(
              new ModuleGains(
                  new DriveMotorGains(driveP, driveS, driveV),
                  new SteerMotorGains(steerP, steerD, steerS, steerDFilter)));
        });
  }
}
