// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.KilogramSquareMeters;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Volts;

import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.system.DCMotor;
import org.wpilib.math.system.Models;
import org.wpilib.simulation.BatterySim;
import org.wpilib.simulation.DCMotorSim;
import org.wpilib.units.measure.Voltage;

public final class SwerveDriveSim {
  private static final int MODULES = 4;

  private final SwerveDriveKinematics kinematics;
  private final DCMotorSim[] drive = new DCMotorSim[MODULES];
  private final DCMotorSim[] steer = new DCMotorSim[MODULES];
  private final DCMotor driveMotor;
  private final DCMotor steerMotor;
  private final double driveCurrentLimit;
  private final double steerCurrentLimit;
  private final double wheelRadius;
  private final double[] driveAppliedVolts = new double[MODULES];
  private final double[] steerAppliedVolts = new double[MODULES];

  private Pose2d pose = Pose2d.kZero;
  private ChassisVelocities velocity = new ChassisVelocities();
  private double batteryVolts = BatterySim.calculateDefaultBatteryLoadedVoltage();

  public SwerveDriveSim(SwerveSimConfig config) {
    kinematics = new SwerveDriveKinematics(config.moduleLocations());
    wheelRadius = config.wheelRadius().in(Meters);
    driveMotor = config.drive().motor();
    steerMotor = config.steer().motor();
    driveCurrentLimit = config.drive().currentLimit().in(Amps);
    steerCurrentLimit = config.steer().currentLimit().in(Amps);
    for (int i = 0; i < MODULES; i++) {
      drive[i] = axis(config.drive());
      steer[i] = axis(config.steer());
    }
  }

  public SimModuleState[] update(double[] driveVolts, double[] steerVolts, double dtSeconds) {
    for (int i = 0; i < MODULES; i++) {
      driveAppliedVolts[i] = applied(drive[i], driveMotor, driveCurrentLimit, driveVolts[i]);
      steerAppliedVolts[i] = applied(steer[i], steerMotor, steerCurrentLimit, steerVolts[i]);
      drive[i].setInput(driveAppliedVolts[i]);
      steer[i].setInput(steerAppliedVolts[i]);
      drive[i].update(dtSeconds);
      steer[i].update(dtSeconds);
    }

    // DCMotorSim.setInputVoltage clamps against RobotController's battery, which is a HAL read.
    // The sag is modelled here instead, and reaches the motors on the next step's inputs.
    batteryVolts = BatterySim.calculateDefaultBatteryLoadedVoltage(currents());

    var states = moduleStates();
    velocity = kinematics.toChassisVelocities(velocities(states));
    pose = pose.plus(velocity.toTwist2d(dtSeconds).exp());
    return states;
  }

  public SimModuleState[] moduleStates() {
    var states = new SimModuleState[MODULES];
    for (int i = 0; i < MODULES; i++) {
      states[i] =
          new SimModuleState(
              drive[i].getAngularPosition(),
              drive[i].getAngularVelocity(),
              new Rotation2d(steer[i].getAngularPosition()),
              // Always false: free space has no ground contact to break.
              false,
              driveAppliedVolts[i],
              steerAppliedVolts[i]);
    }
    return states;
  }

  public void resetPose(Pose2d pose) {
    this.pose = pose;
  }

  public Pose2d truePose() {
    return pose;
  }

  public ChassisVelocities trueVelocity() {
    return new ChassisVelocities(velocity.vx, velocity.vy, velocity.omega);
  }

  public Voltage batteryVoltage() {
    return Volts.of(batteryVolts);
  }

  // A single-jointed arm with no gravity term is the plain DC motor plant, and DCMotorSim's own
  // javadoc names this factory for it.
  private static DCMotorSim axis(SwerveSimConfig.Axis axis) {
    return new DCMotorSim(
        Models.singleJointedArmFromPhysicalConstants(
            axis.motor(), axis.inertia().in(KilogramSquareMeters), axis.reduction()),
        axis.motor());
  }

  private double applied(DCMotorSim axis, DCMotor motor, double currentLimit, double volts) {
    // The controller enforces the current limit, not physics: free space has nothing to stop a
    // motor drawing stall current from a stop, which collapses the battery model to zero volts.
    double backEmf = axis.getAngularVelocity() * axis.getGearing() / motor.Kv;
    double span = currentLimit * motor.R;
    return Math.clamp(
        Math.clamp(volts, backEmf - span, backEmf + span), -batteryVolts, batteryVolts);
  }

  private double[] currents() {
    var currents = new double[MODULES * 2];
    for (int i = 0; i < MODULES; i++) {
      // Magnitudes: a regenerating motor charges the pack, and a simulation whose battery gains
      // voltage under braking accelerates out of a stop better than the robot ever will.
      currents[i] = Math.abs(drive[i].getCurrentDraw());
      currents[MODULES + i] = Math.abs(steer[i].getCurrentDraw());
    }
    return currents;
  }

  private SwerveModuleVelocity[] velocities(SimModuleState[] states) {
    var velocities = new SwerveModuleVelocity[MODULES];
    for (int i = 0; i < MODULES; i++) {
      velocities[i] =
          new SwerveModuleVelocity(
              states[i].wheelVelocityRadPerSec() * wheelRadius, states[i].azimuth());
    }
    return velocities;
  }
}
