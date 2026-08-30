// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import com.revrobotics.spark.SparkFlex;
import org.wpilib.hardware.hal.SimDouble;
import org.wpilib.simulation.SimDeviceSim;

// The applied output and bus voltage a SPARK reports, driven from the plant. REVLib writes both
// only from SparkSim.iterate, and SparkSim cannot be loaded in this program: as of REVLib-java
// 2027.0.0-alpha-6 it holds a MovingAverageFilterSim, which imports org.wpilib.math.util.Pair — a
// class WPILib moved to org.wpilib.util.Pair. So the two values are written straight to the
// device's SimDevice, which is where SparkSim writes them and where the sensor sims reach theirs.
// Delete this once that import is fixed and SparkFlexSim loads.
final class SparkOutputSim {
  private static final String DEVICE = "SPARK Flex";
  private static final String APPLIED_OUTPUT = "Applied Output";
  private static final String BUS_VOLTAGE = "Bus Voltage";

  private final SimDouble appliedOutput;
  private final SimDouble busVoltage;

  SparkOutputSim(SparkFlex motor) {
    var device =
        new SimDeviceSim(DEVICE + " [" + motor.getBusId() + "," + motor.getDeviceId() + "]");
    appliedOutput = device.getDouble(APPLIED_OUTPUT);
    busVoltage = device.getDouble(BUS_VOLTAGE);
  }

  void set(double volts, double busVolts) {
    // A name the device does not carry comes back null rather than throwing, which is also what a
    // sim built before its SPARK gets.
    if (appliedOutput == null || busVoltage == null) {
      return;
    }
    busVoltage.set(busVolts);
    // Applied output is a duty cycle, and every reader multiplies it back by the rail.
    appliedOutput.set(busVolts == 0 ? 0 : volts / busVolts);
  }
}
