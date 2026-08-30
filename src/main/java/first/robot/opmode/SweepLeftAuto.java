// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Seconds;

import first.robot.FieldConstants;
import first.robot.Robot;
import org.wpilib.command3.Command;
import org.wpilib.command3.Trigger;
import org.wpilib.driverstation.RobotState;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.opmode.Autonomous;
import org.wpilib.opmode.OpMode;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.Distance;

@Autonomous(
    group = "Competition",
    description = "Follows the SweepLeft path, drawn left; Mirrored sweeps right")
public class SweepLeftAuto implements OpMode {
  private static final String ROUTINE = "SweepLeftAuto";
  private static final String PATH = "SweepLeft";

  // Where along the path the zone starts. A pose says it and the clock does not: a robot running
  // 300 ms late crosses the same line, and a time marker fires 300 ms short of it. Read off the
  // path in Choreo, so it arrives in corner coordinates like the path does.
  private static final Distance ZONE_LINE = Meters.of(FieldConstants.fromCornerX(4));

  private final Trigger enabled = new Trigger(RobotState::isEnabled);
  private final Trigger pastZoneLine;
  private final TelemetryTable autoLog = TelemetryRegistry.getTable("/Auto");

  public SweepLeftAuto(Robot robot) {
    // Measured against the path as it was drawn, so the line is in the same place on both
    // alliances and on both sides. Compared raw, this threshold is -4.27 m against a red path
    // running x +6.27 to +2.77, so the trigger is true on the first loop rather than at the zone.
    pastZoneLine =
        new Trigger(
            () ->
                FieldConstants.flipAndMirrorIfNeeded(robot.poseEstimator.getEstimatedPose()).getX()
                    >= ZONE_LINE.in(Meters));
    pastZoneLine.onTrue(markZoneEntry(robot));
    enabled.onTrue(sweepLeft(robot));
  }

  // Both triggers were built here, so both are ours to cancel. Without this a disable leaves the
  // previous opmode's bindings live and the next enable runs two copies of the routine.
  @Override
  public void close() {
    pastZoneLine.unbind();
    enabled.unbind();
  }

  private Command markZoneEntry(Robot robot) {
    return Command.noRequirements(
            coroutine ->
                autoLog.log("ZoneEntry", robot.poseEstimator.getEstimatedPose(), Pose2d.struct))
        .named("Auto.MarkZoneEntry");
  }

  private Command sweepLeft(Robot robot) {
    return Command.noRequirements(
            coroutine -> {
              autoLog.log("RoutineName", ROUTINE);
              // A completion left over from the previous run reads as this one's until this one
              // is over.
              autoLog.log("Complete", false);

              // Seeded from the path the robot is about to drive, after the same alliance flip the
              // follower will apply, so the first sample's error is where the robot was placed.
              robot.poseEstimator.resetPose(robot.trajectory(PATH).start().pose);

              coroutine.fork(elapsed());
              coroutine.await(robot.drive.followPath(PATH));

              // followPath comes back the same way whether the path finished or ran out its
              // timeout, so this has to ask which it was rather than assume.
              autoLog.log("Complete", !robot.drive.lastPathTimedOut());
            })
        .named("Auto.SweepLeft");
  }

  private Command elapsed() {
    return Command.noRequirements(
            coroutine -> {
              var timer = Timer.createStarted();
              while (true) {
                autoLog.log("TimeElapsed", Seconds.of(timer.get()));
                coroutine.yield();
              }
            })
        .named("Auto.TimeElapsed");
  }
}
