// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Seconds;

import java.net.URI;
import org.wpilib.hardware.bus.CANBus;
import org.wpilib.units.measure.Time;

public final class Constants {
  public static final Time LOOP_PERIOD = Milliseconds.of(5);

  // getAlerts() allocates and returns every alert on the robot, and an alert changes on a human
  // timescale, so this is the one signal not written every loop.
  public static final Time ALERT_LOG_PERIOD = Milliseconds.of(250);

  // Every device is on this one bus. REVLib wants its .value, Phoenix wants CANBus.systemcore(n).
  public static final CANBus CAN_BUS = CANBus.CAN_S0;

  public static final int DRIVER_PORT = 0;

  public static final Time RADIO_LOG_PERIOD = Seconds.of(5);
  public static final Time RADIO_TIMEOUT = Milliseconds.of(500);

  // The radio serves its own status page; nothing in WPILib reports on it. 10.TE.AM.1 is the
  // field's addressing convention, so the team number is the whole of the address.
  public static final URI RADIO_STATUS =
      URI.create(
          "http://10."
              + BuildMetadata.TEAM_NUMBER / 100
              + "."
              + BuildMetadata.TEAM_NUMBER % 100
              + ".1/status");

  private Constants() {}
}
