// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.KilogramSquareMeters;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Volts;

import org.wpilib.math.geometry.Pose2d;
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
  // Halves a 12 V bracket to under a microvolt.
  private static final int RAIL_ITERATIONS = 24;
  private final double[] driveAppliedVolts = new double[MODULES];
  private final double[] steerAppliedVolts = new double[MODULES];

  private Pose2d pose = Pose2d.ZERO;
  private ChassisVelocities velocity = new ChassisVelocities();
  private double batteryVolts = BatterySim.calculateDefaultBatteryLoadedVoltage();
  private double appliedRailVolts = batteryVolts;

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
    // DCMotorSim.setInputVoltage clamps against RobotController's battery, which is a HAL read.
    // The sag is modelled here instead, solved against this step's own draw.
    batteryVolts = solveRail(driveVolts, steerVolts);
    appliedRailVolts = batteryVolts;
    for (int i = 0; i < MODULES; i++) {
      driveAppliedVolts[i] =
          applied(drive[i], driveMotor, driveCurrentLimit, driveVolts[i], batteryVolts);
      steerAppliedVolts[i] =
          applied(steer[i], steerMotor, steerCurrentLimit, steerVolts[i], batteryVolts);
      drive[i].setInput(driveAppliedVolts[i]);
      steer[i].setInput(steerAppliedVolts[i]);
      drive[i].update(dtSeconds);
      steer[i].update(dtSeconds);
    }

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
              steer[i].getAngularPosition(),
              steer[i].getAngularVelocity(),
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

  // The rail the last step's applied volts were clamped against, which is the one before that
  // step's sag. Dividing them by any other number can put an applied output outside [-1, 1],
  // where no duty cycle goes.
  public Voltage appliedRailVoltage() {
    return Volts.of(appliedRailVolts);
  }

  // A single-jointed arm with no gravity term is the plain DC motor plant, and DCMotorSim's own
  // javadoc names this factory for it.
  private static DCMotorSim axis(SwerveSimConfig.Axis axis) {
    return new DCMotorSim(
        Models.singleJointedArmFromPhysicalConstants(
            axis.motor(), axis.inertia().in(KilogramSquareMeters), axis.reduction()),
        axis.motor());
  }

  private static double applied(
      DCMotorSim axis, DCMotor motor, double currentLimit, double volts, double rail) {
    // The controller enforces the current limit, not physics: free space has nothing to stop a
    // motor drawing stall current from a stop, which collapses the battery model to zero volts.
    double backEmf = axis.getAngularVelocity() * axis.getGearing() / motor.Kv;
    double span = currentLimit * motor.R;
    return Math.clamp(Math.clamp(volts, backEmf - span, backEmf + span), -rail, rail);
  }

  // The rail and the draw decide each other, so the rail is the one where they agree. Taking the
  // draw from the step before instead is a loop whose gain is the motors' combined conductance
  // over the pack's: four drives at full duty through 20 milliohms is about 1.4, so every step
  // overcorrects the last and the rail rings between the sag and the nominal on alternate steps.
  //
  // Bisection, because the draw only grows as the rail rises and the root is bracketed by a dead
  // rail, which draws nothing, and the nominal, which cannot be exceeded.
  private double solveRail(double[] driveVolts, double[] steerVolts) {
    double low = 0;
    double high = BatterySim.calculateDefaultBatteryLoadedVoltage();
    for (int iteration = 0; iteration < RAIL_ITERATIONS; iteration++) {
      double rail = (low + high) / 2;
      double loaded =
          BatterySim.calculateDefaultBatteryLoadedVoltage(currents(driveVolts, steerVolts, rail));
      if (loaded > rail) {
        low = rail;
      } else {
        high = rail;
      }
    }
    return low;
  }

  private double[] currents(double[] driveVolts, double[] steerVolts, double rail) {
    var currents = new double[MODULES * 2];
    for (int i = 0; i < MODULES; i++) {
      currents[i] = supplyCurrent(drive[i], driveMotor, driveCurrentLimit, driveVolts[i], rail);
      currents[MODULES + i] =
          supplyCurrent(steer[i], steerMotor, steerCurrentLimit, steerVolts[i], rail);
    }
    return currents;
  }

  // What the battery sees, not what the winding sees. A half-bridge is a DC-DC converter: it
  // trades the rail's volts for the motor's amps, so a motor held at its current limit down at a
  // couple of volts costs the pack a fraction of that current. Charging the pack with the winding
  // current instead collapses the rail at exactly the moment a robot launches, and the sag then
  // caps the volts that were going to accelerate it.
  private static double supplyCurrent(
      DCMotorSim axis, DCMotor motor, double currentLimit, double volts, double rail) {
    double appliedVolts = applied(axis, motor, currentLimit, volts, rail);
    double motorAmps =
        motor.getCurrent(axis.getAngularVelocity() * axis.getGearing(), appliedVolts)
            * Math.signum(appliedVolts);
    // The current is signed against the bus: negative is a motor pushing power back into it.
    // A braking motor is credited as nothing rather than as charge, because a simulation whose
    // battery gains voltage under braking accelerates out of a stop better than the robot ever
    // will — but nothing is also not a full load, which is what taking the magnitude made it. A
    // hard stop then sagged the rail into the clamp that was holding the wheels back.
    if (motorAmps <= 0 || rail == 0) {
      return 0;
    }
    return motorAmps * Math.abs(appliedVolts) / rail;
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
