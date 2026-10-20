package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.MinioFileStorage;
import com.example.hangat.common.storage.StoredImage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaCleanupJobServiceTest {
    @Test void reportsObjectFailuresAndContinuesOtherObjects() {
        var storage = mock(MinioFileStorage.class);
        var service = mock(MediaCleanupService.class);
        var first = new StoredImage("first", Instant.EPOCH, "1");
        var second = new StoredImage("second", Instant.EPOCH, "2");
        when(storage.inventory()).thenReturn(List.of(first, second));
        when(service.inspect(first, true, false)).thenThrow(new IllegalStateException());
        when(service.inspect(second, true, false)).thenReturn(MediaCleanupService.Outcome.PROTECTED);
        var job = new MediaCleanupJobService(storage, service, true);
        assertThatThrownBy(() -> job.run(null)).isInstanceOf(IllegalStateException.class);
        verify(service).inspect(second, true, false);
    }

    @Test void listingFailureIsNotReportedAsSuccess() {
        var storage = mock(MinioFileStorage.class);
        when(storage.inventory()).thenThrow(new IllegalStateException());
        var job = new MediaCleanupJobService(storage, mock(MediaCleanupService.class), true);
        assertThatThrownBy(() -> job.run(null)).isInstanceOf(IllegalStateException.class);
    }
}
