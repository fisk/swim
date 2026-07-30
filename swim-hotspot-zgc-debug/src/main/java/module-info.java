module org.fisk.swim.hotspot.zgc.debug {
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.hotspotzgc.HotSpotZgcDebugPlugin;
}
