// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrajectoryLoaderTest {
  private static final double EPSILON = 1e-9;

  private static final String SAMPLES =
      """
      {"t": 0.0, "x": 1.0, "y": 2.0, "heading": 0.5,
       "vx": 3.0, "vy": 4.0, "omega": 0.25,
       "ax": 5.0, "ay": 6.0, "alpha": 0.75,
       "fx": [1, 2, 3, 4], "fy": [5, 6, 7, 8]},
      {"t": 2.0, "x": 7.0, "y": 8.0, "heading": 1.5,
       "vx": 0.0, "vy": 0.0, "omega": 0.0,
       "ax": 0.0, "ay": 0.0, "alpha": 0.0,
       "fx": [0, 0, 0, 0], "fy": [0, 0, 0, 0]}
      """;

  private static String file(int version, String sampleType, String samples) {
    return """
        {"name": "Path", "version": %d, "snapshot": {}, "params": {},
         "trajectory": {"config": null, "sampleType": %s,
                        "waypoints": [0.0, 2.0], "samples": [%s], "splits": [0]},
         "events": []}
        """
        .formatted(version, sampleType == null ? "null" : "\"" + sampleType + "\"", samples);
  }

  private static TrajectoryLoader loaderOver(Path deploy, String name, String contents)
      throws IOException {
    var choreo = Files.createDirectories(deploy.resolve("choreo"));
    Files.writeString(choreo.resolve(name + ".traj"), contents);
    return new TrajectoryLoader(deploy);
  }

  @Test
  void theFlatSampleShapeBecomesTheNestedOne(@TempDir Path deploy) throws IOException {
    var trajectory = loaderOver(deploy, "Path", file(3, "Swerve", SAMPLES)).get("Path");

    assertEquals(2, trajectory.getSamples().size());
    assertEquals(2.0, trajectory.duration, EPSILON);

    var sample = trajectory.start();
    assertEquals(0.0, sample.time, EPSILON);
    assertEquals(1.0, sample.pose.getX(), EPSILON);
    assertEquals(2.0, sample.pose.getY(), EPSILON);
    assertEquals(0.5, sample.pose.getRotation().getRadians(), EPSILON);
    assertEquals(3.0, sample.velocity.vx, EPSILON);
    assertEquals(4.0, sample.velocity.vy, EPSILON);
    assertEquals(0.25, sample.velocity.omega, EPSILON);
    assertEquals(5.0, sample.acceleration.ax, EPSILON);
    assertEquals(6.0, sample.acceleration.ay, EPSILON);
    assertEquals(0.75, sample.acceleration.alpha, EPSILON);
  }

  @Test
  void anUnexpectedSchemaVersionIsRejected(@TempDir Path deploy) {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> loaderOver(deploy, "Path", file(4, "Swerve", SAMPLES)));
    assertTrue(thrown.getMessage().contains("4"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("Path.traj"), thrown.getMessage());
  }

  @Test
  void aDifferentialTrajectoryIsRejected(@TempDir Path deploy) {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> loaderOver(deploy, "Path", file(3, "Differential", SAMPLES)));
    assertTrue(thrown.getMessage().contains("Differential"), thrown.getMessage());
  }

  @Test
  void anUngeneratedTrajectoryIsRejected(@TempDir Path deploy) {
    var thrown =
        assertThrows(
            IllegalStateException.class, () -> loaderOver(deploy, "Path", file(3, null, "")));
    assertTrue(thrown.getMessage().contains("Path.traj"), thrown.getMessage());
  }

  @Test
  void aTrajectoryThatLastsNoTimeIsRejected(@TempDir Path deploy) {
    var oneSample = SAMPLES.substring(0, SAMPLES.indexOf("},") + 1);
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> loaderOver(deploy, "Path", file(3, "Swerve", oneSample)));
    assertTrue(thrown.getMessage().contains("Path.traj"), thrown.getMessage());
  }

  @Test
  void aMalformedTrajectoryNamesTheFileItCouldNotParse(@TempDir Path deploy) {
    var thrown =
        assertThrows(
            IllegalStateException.class, () -> loaderOver(deploy, "Path", "{\"version\": "));
    assertTrue(thrown.getMessage().contains("Path.traj"), thrown.getMessage());
  }

  @Test
  void anEmptyDirectoryIsRejected(@TempDir Path deploy) throws IOException {
    Files.createDirectories(deploy.resolve("choreo"));
    assertThrows(IllegalStateException.class, () -> new TrajectoryLoader(deploy));
  }

  @Test
  void aMissingDirectoryIsRejected(@TempDir Path deploy) {
    assertThrows(UncheckedIOException.class, () -> new TrajectoryLoader(deploy));
  }

  @Test
  void anUnknownNameNamesWhatWasLoaded(@TempDir Path deploy) throws IOException {
    var loader = loaderOver(deploy, "Path", file(3, "Swerve", SAMPLES));
    var thrown = assertThrows(NoSuchElementException.class, () -> loader.get("Absent"));
    assertTrue(thrown.getMessage().contains("Absent"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("Path"), thrown.getMessage());
  }

  @Test
  void everyCommittedTrajectoryLoads() {
    var loader = new TrajectoryLoader(Path.of("src", "main", "deploy"));

    for (var name : new String[] {"StraightAhead", "SweepLeft"}) {
      var trajectory = loader.get(name);
      assertTrue(trajectory.duration > 0, name);
      assertEquals(0.0, trajectory.start().time, EPSILON, name);
    }
  }
}
