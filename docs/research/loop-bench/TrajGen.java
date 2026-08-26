// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package wpilib.robot;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;

/** Writes a trajectory JSON of a realistic size, so the enable-transition bench has one to load. */
public final class TrajGen {
  private TrajGen() {}

  public static void main(String... args) throws IOException {
    int count = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    String out = args.length > 1 ? args[1] : "/home/systemcore/test.traj";

    List<HolonomicSample> samples = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      double t = i * 0.01;
      samples.add(
          new HolonomicSample(
              t,
              new Pose2d(4.0 + 3.0 * Math.cos(t), 4.0 + 3.0 * Math.sin(t), new Rotation2d(t * 0.4)),
              new ChassisVelocities(3.0 * Math.cos(t), 3.0 * Math.sin(t), 0.4),
              new ChassisAccelerations(-3.0 * Math.sin(t), 3.0 * Math.cos(t), 0.0)));
    }

    String json = Jsonb.instance().type(HolonomicTrajectory.class).toJson(new HolonomicTrajectory(samples));
    Files.writeString(Path.of(out), json);
    System.out.printf("TRAJGEN wrote %s samples=%d bytes=%d%n", out, count, json.length());
  }
}
