ACLOCAL_AMFLAGS = ${ACLOCAL_FLAGS} -I m4

lib_LTLIBRARIES = libarduino.la
libarduino_la_SOURCES = HardwareSerial.cpp \
	Print.cpp \
	WMath.cpp \
	WString.cpp \
	main.cpp \
	wiring.c \
	wiring_digital.c \
	wiring_shift.c

arduino_includedir=$(includedir)/arduino
arduino_include_HEADERS = HardwareSerial.h \
	Print.h \
	Stream.h \
	WCharacter.h \
	WConstants.h \
	WString.h \
	binary.h \
	virtual_main.h \
	wiring.h

pkgconfigdir = $(libdir)/pkgconfig
pkgconfig_DATA = arduino.pc
