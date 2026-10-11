//! Biome lookup of `MultiNoiseBiomeSource`: the climate sampled on the 4x4x4 grid of
//! `createResolverForChunk`, then vanilla's `Climate.RTree` search on the tree exactly as
//! the server built it (exported, not rebuilt), with the same per-thread last result.

use std::cell::Cell;

use serde_json::Value;

use crate::density::{Context, Graph, LoadError, NodeId};
use crate::noise::DensityVolume;

/// One node of `Climate.RTree`: 7 parameter ranges, then children or a biome id.
struct Node {
    space: [(i64, i64); 7],
    children: Vec<usize>,
    biome: Option<u32>,
}

pub struct BiomeTree {
    nodes: Vec<Node>,
    root: usize,
    /// The six climate density functions, in `Climate.target` order.
    climate: [NodeId; 6],
    /// A copy of the graph without approximation (`approx`), so biomes stay exact.
    exact: Option<Graph>,
}

thread_local! {
    /// `RTree.lastResult`: (tree address, leaf) of this thread's previous search.
    static LAST: Cell<(usize, usize)> = const { Cell::new((0, usize::MAX)) };
}

/// `Climate.quantizeCoord`
fn quantize(v: f32) -> i64 {
    (v * 10000.0f32) as i64
}

impl BiomeTree {
    /// `biome_tree` of the export; `graph` holds the `climate_*` roots. `exact` is set when
    /// the main graph is approximate.
    pub fn from_json(v: &Value, graph: &Graph, exact: Option<Graph>) -> Result<BiomeTree, LoadError> {
        let names = ["climate_temperature", "climate_humidity", "climate_continentalness", "climate_erosion", "climate_depth", "climate_weirdness"];
        let source = exact.as_ref().unwrap_or(graph);
        let mut climate = [0; 6];
        for (slot, name) in climate.iter_mut().zip(names) {
            *slot = source.root(name).ok_or_else(|| LoadError(format!("missing root {name}")))?;
        }
        let mut nodes = Vec::new();
        let root = parse(v, &mut nodes)?;
        Ok(BiomeTree { nodes, root, climate, exact })
    }

    /// Biome ids for the quarts of a chunk column from `(min_x, min_y, min_z)`, `quarts_y`
    /// high; index `(y * 4 + z) * 4 + x`.
    pub fn fill(&self, graph: &Graph, min_x: i32, min_y: i32, min_z: i32, quarts_y: i32, out: &mut [i32]) {
        let graph = self.exact.as_ref().unwrap_or(graph);
        let volume = DensityVolume::new([4, quarts_y, 4], [min_x, min_y, min_z], [4, 4, 4]);
        let mut ctx = Context::new(graph);
        let values: Vec<Vec<f32>> = self.climate.iter().map(|&node| ctx.sample_volume_new(node, &volume)).collect();
        let key = self as *const BiomeTree as usize;
        let mut last = LAST.with(|l| {
            let (tree, leaf) = l.get();
            if tree == key {
                Some(leaf)
            } else {
                None
            }
        });
        for y in 0..quarts_y {
            for z in 0..4 {
                for x in 0..4 {
                    let i = volume.index_unchecked(x, y, z);
                    let target = [
                        quantize(values[0][i]),
                        quantize(values[1][i]),
                        quantize(values[2][i]),
                        quantize(values[3][i]),
                        quantize(values[4][i]),
                        quantize(values[5][i]),
                        0,
                    ];
                    let leaf = self.search(self.root, &target, last);
                    last = Some(leaf);
                    out[((y * 4 + z) * 4 + x) as usize] = self.nodes[leaf].biome.unwrap_or(0) as i32;
                }
            }
        }
        for v in values {
            crate::density::recycle([v]);
        }
        if let Some(leaf) = last {
            LAST.with(|l| l.set((key, leaf)));
        }
    }

    /// `RTree.Node.distance`
    fn distance(&self, node: usize, target: &[i64; 7]) -> i64 {
        let mut d = 0i64;
        for (i, &(min, max)) in self.nodes[node].space.iter().enumerate() {
            let above = target[i].wrapping_sub(max);
            let below = min.wrapping_sub(target[i]);
            let p = if above > 0 { above } else { below.max(0) };
            d = d.wrapping_add(p.wrapping_mul(p));
        }
        d
    }

    /// `RTree.SubTree.search` / `Leaf.search`
    fn search(&self, node: usize, target: &[i64; 7], candidate: Option<usize>) -> usize {
        if self.nodes[node].biome.is_some() {
            return node;
        }
        let mut min = candidate.map_or(i64::MAX, |c| self.distance(c, target));
        let mut closest = candidate;
        for &child in &self.nodes[node].children {
            let child_distance = self.distance(child, target);
            if min > child_distance {
                let leaf = self.search(child, target, closest);
                let leaf_distance = if child == leaf { child_distance } else { self.distance(leaf, target) };
                if min > leaf_distance {
                    min = leaf_distance;
                    closest = Some(leaf);
                }
            }
        }
        closest.expect("R-tree search without result")
    }
}

fn parse(v: &Value, nodes: &mut Vec<Node>) -> Result<usize, LoadError> {
    let bad = || LoadError("biome_tree node".into());
    let p = v.get("p").and_then(Value::as_array).ok_or_else(bad)?;
    if p.len() != 14 {
        return Err(bad());
    }
    let mut space = [(0i64, 0i64); 7];
    for (i, s) in space.iter_mut().enumerate() {
        *s = (p[2 * i].as_i64().ok_or_else(bad)?, p[2 * i + 1].as_i64().ok_or_else(bad)?);
    }
    let index = nodes.len();
    nodes.push(Node { space, children: Vec::new(), biome: None });
    if let Some(b) = v.get("b") {
        nodes[index].biome = Some(b.as_u64().ok_or_else(bad)? as u32);
    } else {
        let mut children = Vec::new();
        for c in v.get("c").and_then(Value::as_array).ok_or_else(bad)? {
            children.push(parse(c, nodes)?);
        }
        nodes[index].children = children;
    }
    Ok(index)
}
