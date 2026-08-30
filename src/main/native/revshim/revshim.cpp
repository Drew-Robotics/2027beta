// Two symbols REVLib 2027.0.0-alpha-6's libREVLibWpi.so imports and no WPILib this project can
// compile against exports. Without them the dynamic linker aborts the JVM the first time a SPARK
// is constructed, which takes simulation, the tests and the deploy with it.
//
// Delete this whole directory once REVLib publishes a build against a current WPILib. The check is
//   ./gradlew test --tests first.robot.WiringTest -PnoRevShim
// which drops the preload; a green run means the shim is dead weight. Today it exits 127.
//
// Built by hand, not by Gradle. Both binaries beside this file come from:
//
//   g++ -shared -fPIC -O2 -s -o linuxx86-64/librevshim.so revshim.cpp
//   ~/.gradle/toolchains/first/2027/systemcore/bin/aarch64-systemcore2027-linux-gnu-g++ \
//       -shared -fPIC -O2 -s -o linuxsystemcore/librevshim.so revshim.cpp
//
// and are checked by `nm -D --defined-only <so>`, which must name both symbols below. The
// SystemCore toolchain is the one allwpilib builds its own natives with; a different aarch64
// compiler risks a std::string ABI the Pi's libstdc++ does not share.

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

// wpi::util::WaitForObject(unsigned int) against wpi::util::WaitForObject(int). WPI_Handle went
// uint32_t to int32_t (wpi/util/Handle.h), which changes the mangled name and nothing else: same
// width, same register, same call. This alias is exact rather than approximate.
extern "C" bool waitForObjectSigned(std::int32_t) asm("_ZN3wpi4util13WaitForObjectEi");

extern "C" bool waitForObjectUnsigned(std::uint32_t) asm("_ZN3wpi4util13WaitForObjectEj");
extern "C" bool waitForObjectUnsigned(std::uint32_t handle) {
  return waitForObjectSigned(static_cast<std::int32_t>(handle));
}

// fmt::v12::vformat, which wpiutil dropped when it moved to std::format. REVLib reaches it once
// per SPARK with the format string "REV_SPARK_Flex[{},{}]", so this is live code, not a placeholder
// for something unreachable: returning the format string unexpanded gives all eight modules the
// same value. Nothing this project reads is downstream of it — SimDevice names are built on a
// different path and come back correctly as "SPARK Flex [0,1]" and so on — but that is a fact
// checked on one REVLib version rather than a guarantee, so the first call says so out loud.
//
// fmt::basic_string_view is {ptr, len} and fmt::basic_format_args is {desc, ptr}: two eightbyte
// registers each, which is what the four parameters below are.
std::string fmtVformat(const char*, std::size_t, unsigned long, const void*)
    asm("_ZN3fmt3v127vformatB5cxx11ENS0_17basic_string_viewIcEENS0_17basic_format_argsINS0_7contextEEE");

std::string fmtVformat(const char* data, std::size_t size, unsigned long, const void*) {
  static bool announced = false;
  if (!announced) {
    announced = true;
    std::fprintf(stderr, "[revshim] fmt::vformat stubbed; format strings are returned unexpanded\n");
  }
  return data == nullptr ? std::string() : std::string(data, size);
}
