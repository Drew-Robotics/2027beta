// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import org.wpilib.math.util.MathUtil;

public final class OnboardLoopSim {
  private final double kP;
  private final double kD;
  private final double kS;
  private final double kV;
  private final double dFilter;
  private final boolean wrapping;
  private final double inputRange;

  private double setpoint;
  private double lastError;
  private double derivative;
  private boolean started;

  private OnboardLoopSim(
      double kP, double kD, double kS, double kV, double dFilter, boolean wrapping, double range) {
    this.kP = kP;
    this.kD = kD;
    this.kS = kS;
    this.kV = kV;
    this.dFilter = dFilter;
    this.wrapping = wrapping;
    this.inputRange = range;
  }

  public static OnboardLoopSim velocity(double kP, double kS, double kV) {
    return new OnboardLoopSim(kP, 0, kS, kV, 0, false, 0);
  }

  // kV is not applied in position mode on the controller, so it is not a parameter here.
  public static OnboardLoopSim position(
      double kP, double kD, double dFilter, double minInput, double maxInput) {
    return new OnboardLoopSim(kP, kD, 0, 0, dFilter, true, maxInput - minInput);
  }

  public void setSetpoint(double setpoint) {
    this.setpoint = setpoint;
  }

  public double calculate(double measurement, double dtSeconds, double busVolts) {
    double error = setpoint - measurement;
    if (wrapping) {
      error = MathUtil.inputModulus(error, -inputRange / 2, inputRange / 2);
    }

    double raw = started ? (error - lastError) / dtSeconds : 0;
    // REVLib's derivative filter carries no units and no documented range, so it is modelled as
    // the fraction of the previous derivative carried forward. Zero is an unfiltered D term.
    derivative = dFilter * derivative + (1 - dFilter) * raw;
    lastError = error;
    started = true;

    // The SPARK's feedback output is a duty cycle and its FeedForwardConfig gains are volts, so
    // only the first pair scales with the rail.
    return busVolts * (kP * error + kD * derivative) + kS * Math.signum(setpoint) + kV * setpoint;
  }
}
