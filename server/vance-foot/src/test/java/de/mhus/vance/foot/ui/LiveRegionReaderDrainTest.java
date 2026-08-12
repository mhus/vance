package de.mhus.vance.foot.ui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class LiveRegionReaderDrainTest {

    @Test
    void drainsFullscreenResidueUntilReaderIsQuiet() throws IOException {
        NonBlockingReader reader = mock(NonBlockingReader.class);
        when(reader.read(25L)).thenReturn(27, (int) '[', (int) '2', (int) '0', (int) '1', (int) '~', -1);

        LiveRegion.drainReaderUntilQuiet(reader, 25L, 250L);

        verify(reader, times(7)).read(25L);
    }
}
