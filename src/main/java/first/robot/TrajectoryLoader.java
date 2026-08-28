// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import io.avaje.jsonb.Json;
import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;

public final class TrajectoryLoader {
  private static final String DIRECTORY = "choreo";
  private static final String EXTENSION = ".traj";
  private static final int SCHEMA_VERSION = 3;
  private static final String SWERVE = "Swerve";

  @Json
  record ChoreoFile(int version, ChoreoTrajectory trajectory) {}

  @Json
  record ChoreoTrajectory(String sampleType, List<ChoreoSample> samples) {}

  @Json
  record ChoreoSample(
      double t,
      double x,
      double y,
      double heading,
      double vx,
      double vy,
      double omega,
      double ax,
      double ay,
      double alpha) {}

  private final Map<String, HolonomicTrajectory> cache;

  public TrajectoryLoader(Path deployDirectory) {
    var directory = deployDirectory.resolve(DIRECTORY);
    try (var files = Files.list(directory)) {
      cache =
          files
              .filter(file -> file.getFileName().toString().endsWith(EXTENSION))
              .collect(Collectors.toMap(TrajectoryLoader::trajectoryName, TrajectoryLoader::read));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list " + directory, e);
    }

    // The deploy artifact does not delete what it no longer ships, so a file tree that failed
    // halfway leaves the directory standing and empty. Reading nothing out of it is not success.
    if (cache.isEmpty()) {
      throw new IllegalStateException("No " + EXTENSION + " file under " + directory);
    }
  }

  public HolonomicTrajectory get(String name) {
    var trajectory = cache.get(name);
    if (trajectory == null) {
      throw new NoSuchElementException(
          "No trajectory " + name + "; loaded " + new TreeSet<>(cache.keySet()));
    }
    return trajectory;
  }

  private static String trajectoryName(Path file) {
    var name = file.getFileName().toString();
    return name.substring(0, name.length() - EXTENSION.length());
  }

  private static HolonomicTrajectory read(Path file) {
    var parsed = parse(file);

    if (parsed.version() != SCHEMA_VERSION) {
      throw new IllegalStateException(
          file + " is schema version " + parsed.version() + ", not " + SCHEMA_VERSION);
    }

    var choreo = parsed.trajectory();
    var samples = choreo == null ? null : choreo.samples();
    if (samples == null || samples.isEmpty()) {
      throw new IllegalStateException(file + " has no samples; generate it in Choreo");
    }
    if (!SWERVE.equals(choreo.sampleType())) {
      throw new IllegalStateException(
          file + " is a " + choreo.sampleType() + " trajectory, not " + SWERVE);
    }

    var trajectory =
        new HolonomicTrajectory(samples.stream().map(TrajectoryLoader::convert).toList());
    // duration is the last sample's timestamp, so one sample, or several sharing a timestamp,
    // gives a trajectory a follower reports done on before the robot has moved.
    if (trajectory.duration <= 0) {
      throw new IllegalStateException(file + " lasts no time; generate it in Choreo");
    }
    return trajectory;
  }

  private static ChoreoFile parse(Path file) {
    try (var stream = Files.newInputStream(file)) {
      return Jsonb.instance().type(ChoreoFile.class).fromJson(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    } catch (RuntimeException e) {
      // avaje reports a byte offset and no filename, which does not say which of the paths under
      // the directory is the broken one.
      throw new IllegalStateException("Cannot parse " + file, e);
    }
  }

  // Both formats state the velocity and the acceleration in the field frame, so the whole mapping
  // is a reshaping and there is no rotation anywhere in it.
  private static HolonomicSample convert(ChoreoSample sample) {
    return new HolonomicSample(
        sample.t(),
        new Pose2d(sample.x(), sample.y(), new Rotation2d(sample.heading())),
        new ChassisVelocities(sample.vx(), sample.vy(), sample.omega()),
        new ChassisAccelerations(sample.ax(), sample.ay(), sample.alpha()));
  }
}
