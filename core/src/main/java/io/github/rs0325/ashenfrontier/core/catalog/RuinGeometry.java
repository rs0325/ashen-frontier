package io.github.rs0325.ashenfrontier.core.catalog;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** モデルと衝突で共有する16分割の固定形状。基準面は北、梁の基準軸はY。 */
enum RuinGeometry {
    SPALLED_RC(new int[][]{{0,0,5,16,16,16},{0,0,0,6,10,5},{3,1,2,5,16,4},{11,0,2,13,16,4},{1,4,1,15,6,3},{1,12,1,15,14,3}}, 2),
    SEVERE_RC(new int[][]{{0,0,7,7,11,16},{0,0,0,5,7,7},{3,2,2,6,15,4},{11,0,2,13,8,4},{2,4,1,15,6,3},{2,12,1,8,14,3}}, 2),
    REBAR(new int[][]{{3,0,7,5,16,9},{11,0,7,13,16,9},{0,3,8,16,5,10},{0,11,8,16,13,10}}),
    CORRODED_REBAR(new int[][]{{3,0,7,5,9,9},{3,12,7,5,16,9},{11,0,7,14,14,9},{0,3,8,13,5,10},{5,11,8,16,13,10}}),
    BROKEN_CONCRETE(new int[][]{{0,0,0,16,7,16},{0,7,3,9,13,16},{0,13,7,6,16,16}}),
    HOLLOW_CONCRETE(new int[][]{{0,0,0,16,16,3},{0,0,13,12,16,16},{0,0,3,3,16,13},{7,0,3,10,12,13},{13,0,3,16,7,13}}),
    BROKEN_BRICK(new int[][]{{0,0,0,16,6,16},{0,6,0,12,11,16},{0,11,0,7,16,16},{12,6,7,16,9,16}}),
    RUBBLE(new int[][]{{0,0,2,9,4,12},{8,1,5,16,6,16},{2,4,6,8,8,13},{4,1,0,13,3,5}}),
    RC_RUBBLE(new int[][]{{0,0,2,9,4,12},{8,1,5,16,6,16},{2,4,6,8,8,13},{4,1,0,13,3,5},{3,7,7,15,9,9},{11,0,6,13,12,10}}, 4),
    STEEL_RUBBLE(new int[][]{{0,0,2,13,2,6},{3,2,3,15,4,5},{5,0,8,16,3,15},{2,0,9,4,7,14}}),
    WOOD_RUBBLE(new int[][]{{0,0,1,13,3,5},{4,0,7,16,2,11},{2,3,2,5,6,14},{7,2,9,14,5,14}}),
    ASPHALT_FRAGMENT(new int[][]{{0,0,2,12,2,14},{4,2,4,10,4,11},{12,0,5,16,1,12}}),
    BEAM(new int[][]{{0,0,0,16,16,3},{7,0,3,9,16,13},{0,0,13,16,16,16}}),
    DAMAGED_BEAM(new int[][]{{0,0,0,16,7,3},{0,7,0,10,16,3},{7,0,3,9,12,13},{0,0,13,16,9,16},{4,9,13,16,16,16}}),
    WINDOW(new int[][]{{0,0,7,16,16,9}}),
    BROKEN_WINDOW(new int[][]{{0,0,7,16,2,9},{0,2,7,2,16,9},{14,2,7,16,16,9},{2,14,7,14,16,9},{2,2,7,7,6,9},{11,10,7,14,14,9}}),
    BROKEN_TIMBER(new int[][]{{0,0,0,16,8,16},{0,8,2,8,16,16},{8,8,9,13,13,16}}),
    SPLIT_BOARD(new int[][]{{0,0,7,7,16,9},{8,0,7,16,11,9},{9,11,7,16,16,9}}),
    ROTTEN_BOARD(new int[][]{{0,0,7,6,12,9},{8,0,7,16,7,9},{2,12,7,6,16,9}}),
    SHEET(new int[][]{{0,0,7,16,16,9}}),
    CORRUGATED(new int[][]{{0,0,6,4,16,8},{4,0,8,8,16,10},{8,0,6,12,16,8},{12,0,8,16,16,10}}),
    PERFORATED_SHEET(new int[][]{{0,0,7,16,3,9},{0,3,7,3,16,9},{13,3,7,16,13,9},{3,13,7,16,16,9},{3,7,7,8,10,9},{10,3,7,13,8,9}}),
    PERFORATED_CORRUGATED(new int[][]{{0,0,6,4,16,8},{4,0,8,8,3,10},{4,13,8,8,16,10},{8,0,6,12,7,8},{8,10,6,12,16,8},{12,0,8,16,16,10}}),
    REMNANT_CORRUGATED(new int[][]{{0,0,6,4,12,8},{4,0,8,8,7,10},{8,0,6,12,4,8},{12,0,8,16,2,10}}),
    REMNANT_SHEET(new int[][]{{0,0,7,6,9,9},{0,9,7,3,16,9},{6,0,7,13,4,9},{13,0,7,16,2,9}});

    final int[][] boxes;
    final int rebarFrom;

    RuinGeometry(int[][] boxes) { this(boxes, boxes.length); }
    RuinGeometry(int[][] boxes, int rebarFrom) {
        this.boxes = boxes;
        this.rebarFrom = rebarFrom;
    }

    int[][] oriented(Direction direction) {
        int turns = switch (direction) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        var result = new int[boxes.length][6];
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i].clone();
            for (int j = 0; j < turns; j++) b = new int[]{16-b[5],b[1],b[0],16-b[2],b[4],b[3]};
            result[i] = b;
        }
        return result;
    }

    VoxelShape shape(int[][] elements) {
        VoxelShape result = Shapes.empty();
        for (int[] b : elements) result = Shapes.or(result, Block.box(b[0],b[1],b[2],b[3],b[4],b[5]));
        return result;
    }

    int[][] along(Direction.Axis axis) {
        var result = new int[boxes.length][6];
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i];
            result[i] = switch (axis) {
                case X -> new int[]{b[1],b[0],b[2],b[4],b[3],b[5]};
                case Z -> new int[]{b[0],b[2],b[1],b[3],b[5],b[4]};
                case Y -> b.clone();
            };
        }
        return result;
    }
}
