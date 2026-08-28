// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Seconds;

import first.robot.Robot;
import org.wpilib.command3.Command;
import org.wpilib.command3.Trigger;
import org.wpilib.driverstation.RobotState;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Transform2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Utility;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.LinearVelocity;
import org.wpilib.units.measure.Time;

@Utility(group = "Checks", description = "Drives a closed square and reports the odometry residual")
public class DrivePathCheck implements OpMode {
  private static final LinearVelocity LEG_SPEED = MetersPerSecond.of(1);
  private static final Time LEG_DURATION = Seconds.of(1.5);
  private static final Time SETTLE = Seconds.of(0.5);
  private static final Pose2d START = Pose2d.kZero;

  // The square closes, so whatever pose the estimator reports at the end is the whole of the
  // odometry error. Nothing here needs a ground truth, which is what makes it the same check on
  // the floor as in simulation.
  private static final ChassisVelocities[] SQUARE = {
    leg(LEG_SPEED, MetersPerSecond.zero()),
    leg(MetersPerSecond.zero(), LEG_SPEED),
    leg(LEG_SPEED.unaryMinus(), MetersPerSecond.zero()),
    leg(MetersPerSecond.zero(), LEG_SPEED.unaryMinus()),
  };

  private final Trigger enabled = new Trigger(RobotState::isEnabled);
  private final TelemetryTable checkLog =
      TelemetryRegistry.getTable("/Check").getTable("DrivePath");

  public DrivePathCheck(Robot robot) {
    enabled.onTrue(drivePath(robot));
  }

  // The scheduler unbinds a trigger when its opmode's id changes, and a disable does not change
  // it: the framework tears this opmode down and builds a new one around a selection that stayed
  // put. Without this, every re-enable leaves another live copy of the square racing the last.
  @Override
  public void close() {
    enabled.unbind();
  }

  private static ChassisVelocities leg(LinearVelocity vx, LinearVelocity vy) {
    return new ChassisVelocities(vx, vy, RadiansPerSecond.zero());
  }

  private Command drivePath(Robot robot) {
    return Command.noRequirements(
            coroutine -> {
              robot.poseEstimator.resetPose(START);
              // A residual left over from the previous run is indistinguishable from this run's
              // until the run that produced it is over.
              checkLog.log("Complete", false);

              int leg = 0;
              while (leg < SQUARE.length) {
                var velocities = SQUARE[leg];
                coroutine.await(
                    robot.drive.driveRobotRelative(() -> velocities).withTimeout(LEG_DURATION));
                leg++;
              }

              // A leg ends by cancelling its command, which drops the wheels rather than
              // commanding zero, so the robot is still stopping when the fourth one ends. Read it
              // there and the last leg's stopping distance reads as odometry error.
              coroutine.wait(SETTLE);

              // The odometry-only pose, not the fused one. A vision update would correct the
              // residual away and leave this reporting perfect wheels.
              var residual = robot.poseEstimator.getOdometryOnlyPose().toPose2d().minus(START);
              checkLog.log("Residual", residual, Transform2d.struct);
              checkLog.log("ResidualDistance", Meters.of(residual.getTranslation().getNorm()));
              checkLog.log("ResidualRotation", residual.getRotation().getMeasure());
              checkLog.log("Complete", true);
            })
        .named("Check.DrivePath");
  }
}
