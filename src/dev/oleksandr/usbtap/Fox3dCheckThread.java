package dev.oleksandr.usbtap;

/** Runs the Fox3D web-server check off the accessibility event thread, since it opens a
 *  loopback socket to probe port 2525. */
final class Fox3dCheckThread extends Thread {
    private final UsbAllowService service;

    Fox3dCheckThread(UsbAllowService service) {
        this.service = service;
    }

    @Override
    public void run() {
        service.handleFox3dWindowBackground();
    }
}
