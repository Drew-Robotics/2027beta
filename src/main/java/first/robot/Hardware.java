// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import com.ctre.phoenix6.StatusCode;
import com.revrobotics.REVLibError;
import java.util.function.Supplier;
import org.wpilib.util.Alert;

public final class Hardware {
  private static final int MAX_ATTEMPTS = 5;

  // Broad on purpose: the narrower IllegalStateException is only what REVLib documents itself as
  // throwing today, and nothing a device does may take out Robot's constructor.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static void configureSpark(String name, Supplier<REVLibError> apply) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      REVLibError status;
      try {
        status = apply.get();
      } catch (RuntimeException e) {
        // REVLib splits its failures across two channels: kTimeout and
        // kCannotPersistParametersWhileEnabled come back as values, and every other error arrives
        // as an unchecked throw. A throw can also land after the write succeeded, so it is never
        // retried.
        raise(name, e.getMessage());
        return;
      }
      if (status == REVLibError.kOk) {
        return;
      }
      if (status != REVLibError.kTimeout) {
        raise(name, status.name());
        return;
      }
    }
    raise(name, "timed out after " + MAX_ATTEMPTS + " attempts");
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static void configurePhoenix(String name, Supplier<StatusCode> apply) {
    StatusCode status;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        status = apply.get();
      } catch (RuntimeException e) {
        raise(name, e.getMessage());
        return;
      }
      if (status.isOK()) {
        return;
      }
    }
    raise(name, "not OK after " + MAX_ATTEMPTS + " attempts");
  }

  private static void raise(String name, String detail) {
    new Alert(name, "Config failed: " + detail, Alert.Level.HIGH).set(true);
  }

  private Hardware() {}
}
