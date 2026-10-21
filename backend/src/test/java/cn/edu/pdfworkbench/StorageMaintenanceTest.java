package cn.edu.pdfworkbench;

import static org.mockito.Mockito.*;
import cn.edu.pdfworkbench.storage.*;
import cn.edu.pdfworkbench.tasks.TaskSettings;
import org.junit.jupiter.api.Test;

class StorageMaintenanceTest {
  @Test void workerReapsOnIndependentScheduler() {
    var files=mock(DocumentStorage.class);
    var service=new StorageMaintenance(files,new TaskSettings("pdf-workbench.unit","worker",30,128,3,900),1);
    try { service.start(); verify(files,timeout(4000)).reap(8); }
    finally { service.stop(); }
  }
  @Test void webRoleDoesNotStartFileRecovery() {
    var files=mock(DocumentStorage.class);
    var service=new StorageMaintenance(files,new TaskSettings("pdf-workbench.unit","web",30,128,3,900),1);
    try { service.start(); verify(files,after(1200).never()).reap(anyInt()); }
    finally { service.stop(); }
  }
}
