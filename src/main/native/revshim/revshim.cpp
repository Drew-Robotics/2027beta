// REVLib 2027.0.0-alpha-6 disagrees with the WPILib this project compiles against in two
// unrelated ways, and this file bridges both. Without it the dynamic linker aborts the JVM the
// first time a SPARK is constructed, which takes simulation, the tests and the deploy with it;
// with only the first half of it, the robot dies on the SystemCore the first time REVLib prints.
//
//   1. Two symbols REVLib imports that no WPILib this project can compile against exports.
//   2. Two console entry points that resolve and then misread their arguments, because
//      allwpilib 6e5171cd8 (2026-06-06) changed their signatures and they are extern "C".
//
// Delete this whole directory once REVLib publishes a build against a current WPILib. The check
// is in two parts, because the desktop half cannot see the SystemCore half:
//
//   ./gradlew test --tests first.robot.WiringTest -PnoRevShim
//   ./gradlew deploy, with the LD_PRELOAD dropped from build.gradle's javaCommand, reaching
//     "Robot program startup complete" on the Pi
//
// Green on both means the shim is dead weight. Today the first exits 127 and the second aborts.
//
// Built by hand, not by Gradle. Both binaries beside this file come from:
//
//   g++ -shared -fPIC -O2 -s -o linuxx86-64/librevshim.so revshim.cpp -ldl
//   ~/.gradle/toolchains/first/2027/systemcore/bin/aarch64-systemcore2027-linux-gnu-g++ \
//       -shared -fPIC -O2 -s -o linuxsystemcore/librevshim.so revshim.cpp -ldl
//
// and are checked by `nm -D --defined-only <so>`, which must name all four symbols below. The
// SystemCore toolchain is the one allwpilib builds its own natives with; a different aarch64
// compiler risks a std::string ABI the Pi's libstdc++ does not share.

#ifndef _GNU_SOURCE
#define _GNU_SOURCE  // RTLD_NEXT
#endif
#include <dlfcn.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
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

namespace {

struct WpiString {
  const char* str;
  std::size_t len;
};

WpiString wrap(const char* s) {
  return WpiString{s == nullptr ? "" : s, s == nullptr ? 0 : std::strlen(s)};
}

// The four natives vendordeps/REVLib.json pins. Written out rather than matched as a libREVLib*
// prefix, which libBackendDriver.so does not have. A REVLib release that renames or splits one of
// these moves its calls to the pass-through branch silently.
bool callerIsRevLib(void* returnAddress) {
  Dl_info info;
  if (returnAddress == nullptr || dladdr(returnAddress, &info) == 0 || info.dli_fname == nullptr) {
    return false;
  }
  const char* slash = std::strrchr(info.dli_fname, '/');
  const char* base = slash == nullptr ? info.dli_fname : slash + 1;
  return std::strcmp(base, "libREVLibWpi.so") == 0 ||
         std::strcmp(base, "libREVLibDriver.so") == 0 ||
         std::strcmp(base, "libBackendDriver.so") == 0 ||
         std::strcmp(base, "libREVLib.so") == 0;
}

void announceOnce() {
  static bool announced = false;
  if (!announced) {
    announced = true;
    std::fprintf(stderr, "[revshim] translating REVLib's pre-MrcLib HAL console calls\n");
  }
}

// RTLD_NEXT searches the global scope past this library, and the JVM loads libwpiHal.so with
// RTLD_LOCAL, so it is usually not there. It is in the process by the time anything prints, so
// ask for it by name; RTLD_NOLOAD returns the existing handle rather than loading a second copy.
void* resolveHal(const char* symbol) {
  if (void* direct = dlsym(RTLD_NEXT, symbol)) {
    return direct;
  }
  static void* hal = dlopen("libwpiHal.so", RTLD_NOW | RTLD_NOLOAD);
  void* found = hal == nullptr ? nullptr : dlsym(hal, symbol);
  if (found == nullptr) {
    std::fprintf(stderr, "[revshim] cannot reach the real %s; console output is being dropped\n",
                 symbol);
  }
  return found;
}

}  // namespace

// HAL_SendError and HAL_SendConsoleLine, defined with the signatures in
// hal/src/main/native/include/wpi/hal/DriverStation.h and preloaded ahead of libwpiHal.so, so both
// ABIs arrive here and are told apart by caller before being forwarded to the real HAL.
//
// The parameters that differ between the two ABIs are declared uintptr_t rather than their header
// types, because REVLib passes pointers where the current signature takes a HAL_Bool. A 32-bit
// argument leaves the upper register bits unspecified, so the current ABI is recovered by
// truncating, and REVLib's by casting — neither reads anything the caller did not write.
extern "C" std::int32_t HAL_SendError(std::int32_t isError, std::int32_t errorCode,
                                      std::uintptr_t a2, std::uintptr_t a3, std::uintptr_t a4,
                                      std::uintptr_t a5) {
  using Fn = std::int32_t (*)(std::int32_t, std::int32_t, const WpiString*, const WpiString*,
                              const WpiString*, std::int32_t);
  static Fn real = reinterpret_cast<Fn>(resolveHal("HAL_SendError"));
  if (real == nullptr) {
    return 0;
  }

  if (!callerIsRevLib(__builtin_return_address(0))) {
    return real(isError, errorCode, reinterpret_cast<const WpiString*>(a2),
                reinterpret_cast<const WpiString*>(a3), reinterpret_cast<const WpiString*>(a4),
                static_cast<std::int32_t>(a5));
  }

  // Old ABI: (isError, errorCode, isLVCode, details, location, callStack, printMsg). isLVCode is
  // dropped, and printMsg is the seventh argument, past what this declaration can reach — passing
  // 1 sends REVLib's errors to the journal as well as the DS, which is where they get read on a
  // bench with no Driver Station attached.
  announceOnce();
  WpiString details = wrap(reinterpret_cast<const char*>(a3));
  WpiString location = wrap(reinterpret_cast<const char*>(a4));
  WpiString callStack = wrap(reinterpret_cast<const char*>(a5));
  return real(isError, errorCode, &details, &location, &callStack, 1);
}

extern "C" std::int32_t HAL_SendConsoleLine(std::uintptr_t line) {
  using Fn = std::int32_t (*)(const WpiString*);
  static Fn real = reinterpret_cast<Fn>(resolveHal("HAL_SendConsoleLine"));
  if (real == nullptr) {
    return 0;
  }

  if (!callerIsRevLib(__builtin_return_address(0))) {
    return real(reinterpret_cast<const WpiString*>(line));
  }

  announceOnce();
  WpiString wrapped = wrap(reinterpret_cast<const char*>(line));
  return real(&wrapped);
}
