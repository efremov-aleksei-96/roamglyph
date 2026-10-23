package com.sensareth.roamglyph.map;

import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Geometry acceptance tests, not just class-load or alpha safety checks. */
public final class FogOrganicContourTest {
    @Test public void eliminatesRepeatedHexTeethRatherThanMerelyBlurringTheirEdge() {
        final int w = 192, h = 130;
        int[] shape = new int[w * h];
        // Hard, alternating 14px H3 "teeth" on a long otherwise-solid edge.
        for (int x = 24; x < w - 24; x++) {
            int top = ((x / 9) & 1) == 0 ? 35 : 49;
            for (int y = top; y < 112; y++) shape[y * w + x] = 0xFFFFFFFF;
        }
        FogOrganicContour.renderInPlace(shape, w, h, 12, 7, () -> false);
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int x = 45; x < 145; x += 4) {
            int top = topAt(shape, w, h, x);
            assertTrue("Explored region remains visible", top < 80);
            lowest = Math.min(lowest, top);
            highest = Math.max(highest, top);
        }
        assertTrue("Raw tooth amplitude was 14px; contour must significantly reduce it",
                highest - lowest <= 9);
    }

    @Test public void respectsBoundedOutwardDisclosure() {
        int w = 100, h = 100;
        int[] shape = new int[w * h];
        for (int y = 35; y <= 64; y++) {
            for (int x = 35; x <= 64; x++) shape[y*w+x] = 0xFFFFFFFF;
        }
        FogOrganicContour.renderInPlace(shape, w, h, 8, 3, () -> false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x < 32 || x > 67 || y < 32 || y > 67) {
                    assertEquals("Never reveal more than 3 source pixels away",
                            0, shape[y*w+x] >>> 24);
                }
            }
        }
        assertEquals(255, shape[50*w+50] >>> 24);
    }

    @Test public void diagonalUnvisitedPocketStaysDarkBeyondEuclideanExteriorBudget() {
        int w = 51, h = 51, cx = 25, cy = 25;
        int[] coverage = new int[w * h];
        Arrays.fill(coverage, 0xFFFFFFFF);
        // An unvisited circular pocket whose nearest visited sample is
        // sqrt(5) pixels away from its centre. The old square-integral
        // neighbourhood included diagonal source pixels with dx=2,dy=1
        // and falsely cleared the centre despite a 2-pixel radius cap.
        for (int y = cy - 2; y <= cy + 2; y++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= 4) coverage[y * w + x] = 0;
            }
        }
        FogOrganicContour.renderInPlace(coverage, w, h, 4, 2, () -> false);
        assertEquals("2.5m budget must be Euclidean, not a square", 0,
                coverage[cy * w + cx] >>> 24);
        assertEquals("Surrounding explored geography must remain open", 255,
                coverage[10 * w + 10] >>> 24);
    }

    @Test public void zeroExteriorBudgetCannotOpenUnvisitedPixels() {
        int w = 37, h = 31;
        int[] coverage = new int[w * h];
        for (int y = 9; y <= 23; y++) {
            for (int x = 7; x <= 29; x++) coverage[y * w + x] = 0xFFFFFFFF;
        }
        FogOrganicContour.renderInPlace(coverage, w, h, 5, 0, () -> false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y < 9 || y > 23 || x < 7 || x > 29) {
                    assertEquals("Zero-budget contour must never expand", 0,
                            coverage[y * w + x] >>> 24);
                }
            }
        }
    }

    @Test public void preservesConnectedTwentyMetreCorridors() {
        int w=120,h=80;
        int[] route = new int[w*h];
        for(int y=31;y<49;y++) for(int x=8;x<112;x++) route[y*w+x]=0xFFFFFFFF;
        FogOrganicContour.renderInPlace(route,w,h,10,5,()->false);
        for(int x=25;x<95;x+=5) {
            assertTrue("Accepted GPS corridor is not broken",
                    (route[40*w+x]>>>24)>180);
        }
    }

    @Test public void emptyAndDenseFieldsRemainStable() {
        int[] empty = new int[60*50];
        FogOrganicContour.renderInPlace(empty,60,50,7,4,()->false);
        for(int a:empty) assertEquals(0,a>>>24);
        int[] full = new int[60*50];
        Arrays.fill(full,0xFFFFFFFF);
        FogOrganicContour.renderInPlace(full,60,50,7,4,()->false);
        for(int a:full) assertEquals(255,a>>>24);
    }

    @Test(expected=CancellationException.class)
    public void cancelledGeometryCannotPublishStaleTile() {
        FogOrganicContour.renderInPlace(new int[64*64],64,64,8,3,()->true);
    }

    @Test public void neighbouringWorldTilesShareExactlyTheSameContour() {
        int w=220,h=120,r=8,e=4;
        int[] full = source(-30,-25,w,h);
        FogOrganicContour.renderInPlace(full,w,h,r,e,()->false);
        for(int tile=0;tile<2;tile++) {
            int originX = tile*60;
            int[] padded = source(originX-30,-25,120,h);
            FogOrganicContour.renderInPlace(padded,120,h,r,e,()->false);
            for(int y=32;y<90;y++) {
                for(int x=0;x<60;x++) {
                    int expected=full[y*w+(originX+x+30)]>>>24;
                    int actual=padded[y*120+x+30]>>>24;
                    assertEquals("World tile seam on same geographic pixels",
                            expected,actual);
                }
            }
        }
    }

    private static int[] source(int gx,int gy,int width,int height) {
        int[] result=new int[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++){
            int px=x+gx, py=y+gy;
            double boundary=44+8*Math.sin(px*0.15)+3*Math.sin(px*0.6);
            if(px>=2 && px<=103 && py>boundary && py<92) {
                result[y*width+x]=0xFFFFFFFF;
            }
        }
        return result;
    }

    private static int topAt(int[] shape,int w,int h,int x) {
        for(int y=0;y<h;y++) if((shape[y*w+x]>>>24)>=128) return y;
        return h;
    }
}
