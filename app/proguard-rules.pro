# Add project specific ProGuard rules here.
-keep class org.maplibre.gl.** { *; }
-dontwarn org.slf4j.**

# GraphHopper's OSM PBF reader (com.graphhopper.reader.osm.OSMInputFile and
# com.graphhopper.reader.osm.pbf.*) references the Osmosis OSM-binary classes
# and the protobuf-java classes they are built on. app/build.gradle.kts
# excludes that jar (LGPL-3.0; protobuf-java only came with it) because the
# app never imports a PBF on the device: graphs are built on the desktop and
# only loaded here, so the reader is dead code. These rules only stop R8 from
# failing on those unreachable references; they keep nothing.
-dontwarn org.openstreetmap.osmosis.osmbinary.**
-dontwarn com.google.protobuf.ByteString
-dontwarn com.google.protobuf.ProtocolStringList

# GraphHopper loads a graph by reading its stored profiles and encoded values
# back through Jackson (EncodedValueSerializer, CustomModel, PMap), which
# constructs classes and reads fields reflectively. Keep GraphHopper (and the
# app's pre-compiled MotorcycleWeightingHelper in its package) and Jackson
# as they are; R8 still shrinks and optimises everything else.
-keep class com.graphhopper.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.core.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Desktop-only APIs GraphHopper's dependencies reference from code the app
# never runs: Jackson's XML format (StAX), Apache XML Graphics and AWT image
# writing for elevation tiles, java.beans hints, OSGi annotations, and the
# PBF reader's protobuf (see above). None exists on Android; without the app
# reaching them, nothing changes at runtime.
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn com.google.protobuf.InvalidProtocolBufferException
-dontwarn java.awt.**
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn javax.imageio.**
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.xml.stream.**
