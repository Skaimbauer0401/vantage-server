//! Executes vanilla's compiled density samplers (`levelgen.densityfunction`, 26.3).
//!
//! The server exports the sampler graph *after* vanilla's optimizer and compiler
//! (`bridge-mod/.../WorldgenExport.java`), so this module only has to run each
//! sampler type exactly like its `sampleVolume` / `sampleValue`. Cache semantics are
//! reproduced too: a point query may return a value from a cached *volume*, and the
//! volume and point paths round differently, so the order of work matters.

#![allow(clippy::neg_cmp_op_on_partial_ord, clippy::manual_memcpy, clippy::explicit_counter_loop)]
// Negated comparisons keep Java's NaN behavior; loops mirror vanilla's for review.

use std::collections::HashMap;
use std::sync::Arc;

use serde_json::Value;

use crate::jmath;
use crate::noise::{lerp, lerp3, DensityVolume, Noise, NoiseStack, PerlinNoise, SimplexNoise, SmearedPerlinNoise};

pub type NodeId = usize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Axis {
    X,
    Y,
    Z,
}

impl Axis {
    fn choose(self, x: i32, y: i32, z: i32) -> i32 {
        match self {
            Axis::X => x,
            Axis::Y => y,
            Axis::Z => z,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum GradientKind {
    Clamped { from: i32, min: i32, max: i32 },
    Repeat { from: i32, range: i32 },
    Mirrored { from: i32, range: i32 },
}

#[derive(Debug, Clone, Copy)]
pub enum UnaryOp {
    Abs,
    Square,
    Cube,
    Sqrt,
    LeakyRelu(f32),
    Reciprocal,
    Negate,
    Squeeze,
    Log,
    Sign,
}

#[derive(Debug, Clone, Copy)]
pub enum RoundType {
    Floor,
    Round,
    Ceil,
    Truncate,
}

#[derive(Debug, Clone, Copy)]
pub enum Metric {
    Euclidean,
    EuclideanSquared,
    Manhattan,
    Chebyshev,
}

#[derive(Debug, Clone)]
pub enum Spline {
    Constant(f32),
    Multipoint { sampler: NodeId, index: usize, locations: Vec<f32>, derivatives: Vec<f32>, values: Vec<Spline> },
}

#[derive(Debug, Clone)]
pub enum Node {
    Constant(f32),
    Noise { noise: usize, xz: f64, y: f64 },
    ShiftedXz { sx: NodeId, sz: NodeId, noise: usize, xz: f64, y: f64 },
    ShiftedXyz { sx: NodeId, sy: NodeId, sz: NodeId, noise: usize, xz: f64, y: f64 },
    ShiftB { noise: usize },
    Gradient { axis: Axis, kind: GradientKind, from_value: f32, factor: f32 },
    DistanceToPoint { x: i32, y: i32, z: i32, metric: Metric },
    Add(NodeId, NodeId),
    Sub(NodeId, NodeId),
    Mul(NodeId, NodeId),
    Div(NodeId, NodeId),
    Min(NodeId, NodeId, f32),
    Max(NodeId, NodeId, f32),
    ConstAdd(NodeId, f32),
    ConstSub(f32, NodeId),
    ConstMul(NodeId, f32),
    ConstDiv(f32, NodeId),
    ConstMin(NodeId, f32),
    ConstMax(NodeId, f32),
    Unary(UnaryOp, NodeId),
    Clamp(NodeId, f32, f32),
    Lerp(NodeId, NodeId, NodeId),
    LerpConstFirst(NodeId, f32, NodeId),
    LerpConstSecond(NodeId, NodeId, f32),
    RangeChoice { input: NodeId, min: f32, max: f32, in_range: NodeId, out_range: NodeId },
    RangeChoiceConst { input: NodeId, min: f32, max: f32, in_range: f32, out_range: f32 },
    IntervalSelect { input: NodeId, thresholds: Vec<f32>, samplers: Vec<NodeId> },
    SingleThreshold { input: NodeId, threshold: f32, below: NodeId, above: NodeId },
    Spline { spline: Spline, coordinate_count: usize },
    RoundInt(RoundType, NodeId),
    Round(RoundType, NodeId, NodeId),
    PowConstBase(f64, NodeId),
    PowConstExp(NodeId, f64),
    Pow(NodeId, NodeId),
    /// Blending only applies near old chunks, which stay on vanilla.
    BlendDensity(NodeId),
    FindTopSurface { density: NodeId, upper: NodeId, lower: i32, cell_height: i32 },
    Interpolated { input: NodeId, cell_xz: i32, cell_y: i32, inv_xz: f32, inv_y: f32 },
    SliceX(NodeId, i32),
    SliceY(NodeId, i32),
    SliceZ(NodeId, i32),
    SliceXz(NodeId, i32, i32),
    Cache { id: usize, input: NodeId },
    /// `Beardifier` (structure terrain adaptation), per chunk via [`Context::beard`].
    Beardifier,
}

/// `TerrainAdjustment`
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TerrainAdjustment {
    None,
    Bury,
    BeardThin,
    BeardBox,
    Encapsulate,
}

/// `Beardifier.Rigid`
#[derive(Debug, Clone, Copy)]
pub struct Rigid {
    pub min: [i32; 3],
    pub max: [i32; 3],
    pub adjustment: TerrainAdjustment,
    pub ground_level_delta: i32,
}

/// A chunk's `Beardifier`: structure pieces and jigsaw junctions near it.
#[derive(Debug, Clone, Default)]
pub struct Beard {
    pub pieces: Vec<Rigid>,
    /// `(sourceX, sourceGroundY, sourceZ)`
    pub junctions: Vec<[i32; 3]>,
    /// `affectedBox` as min and max corners; `None` for `Beardifier.EMPTY`.
    pub affected: Option<([i32; 3], [i32; 3])>,
}

/// A compiled sampler graph for one dimension of one world.
pub struct Graph {
    pub nodes: Vec<Node>,
    pub noises: Vec<Arc<dyn Noise>>,
    pub roots: HashMap<String, NodeId>,
    pub cache_count: usize,
    /// `Beardifier.BEARD_KERNEL` (24^3 values).
    pub beard_kernel: Vec<f32>,
}

#[derive(Debug)]
pub struct LoadError(pub String);

impl std::fmt::Display for LoadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

fn err<T>(msg: impl Into<String>) -> Result<T, LoadError> {
    Err(LoadError(msg.into()))
}

fn field<'a>(node: &'a Value, name: &str) -> Result<&'a Value, LoadError> {
    node.get(name).ok_or_else(|| LoadError(format!("missing field {name} in {}", node.get("c").unwrap_or(&Value::Null))))
}

fn as_i64(v: &Value) -> Result<i64, LoadError> {
    v.as_i64().ok_or_else(|| LoadError(format!("expected integer, got {v}")))
}

fn f(node: &Value, name: &str) -> Result<f32, LoadError> {
    Ok(f32::from_bits(as_i64(field(field(node, name)?, "f")?)? as u32))
}

fn d(node: &Value, name: &str) -> Result<f64, LoadError> {
    Ok(f64::from_bits(as_i64(field(field(node, name)?, "d")?)? as u64))
}

fn i(node: &Value, name: &str) -> Result<i32, LoadError> {
    Ok(as_i64(field(field(node, name)?, "i")?)? as i32)
}

fn n(node: &Value, name: &str) -> Result<NodeId, LoadError> {
    Ok(as_i64(field(field(node, name)?, "node")?)? as usize)
}

fn noise_ref(node: &Value, name: &str) -> Result<usize, LoadError> {
    Ok(as_i64(field(field(node, name)?, "noise")?)? as usize)
}

fn enum_name<'a>(node: &'a Value, name: &str) -> Result<&'a str, LoadError> {
    field(field(node, name)?, "enum")?.as_str().ok_or_else(|| LoadError(format!("{name} is not an enum")))
}

fn floats(v: &Value) -> Result<Vec<f32>, LoadError> {
    let array = field(v, "fs")?.as_array().ok_or_else(|| LoadError("fs is not an array".into()))?;
    array.iter().map(|x| Ok(f32::from_bits(as_i64(x)? as u32))).collect()
}

fn axis(name: &str) -> Result<Axis, LoadError> {
    Ok(match name {
        "X" => Axis::X,
        "Y" => Axis::Y,
        "Z" => Axis::Z,
        other => return err(format!("axis {other}")),
    })
}

fn round_type(name: &str) -> Result<RoundType, LoadError> {
    Ok(match name {
        "FLOOR" => RoundType::Floor,
        "ROUND" => RoundType::Round,
        "CEIL" => RoundType::Ceil,
        "TRUNCATE" => RoundType::Truncate,
        other => return err(format!("round type {other}")),
    })
}

fn parse_spline(v: &Value) -> Result<Spline, LoadError> {
    if let Some(c) = v.get("constant") {
        return Ok(Spline::Constant(f32::from_bits(as_i64(c)? as u32)));
    }
    let coordinate = field(field(v, "coordinate")?, "coordinate")?;
    let sampler = n(coordinate, "sampler")?;
    let index = i(coordinate, "index")? as usize;
    let values = field(v, "values")?
        .as_array()
        .ok_or_else(|| LoadError("spline values".into()))?
        .iter()
        .map(parse_spline)
        .collect::<Result<_, _>>()?;
    Ok(Spline::Multipoint {
        sampler,
        index,
        locations: floats(field(v, "locations")?)?,
        derivatives: floats(field(v, "derivatives")?)?,
        values,
    })
}

fn parse_noise(v: &Value, built: &[Arc<dyn Noise>]) -> Result<Arc<dyn Noise>, LoadError> {
    let bits = |name: &str| -> Result<f64, LoadError> { Ok(f64::from_bits(as_i64(field(v, name)?)? as u64)) };
    let gradient = || -> Result<crate::noise::GradientBase, LoadError> {
        let perms_json = field(v, "perms")?.as_array().ok_or_else(|| LoadError("perms".into()))?;
        if perms_json.len() != 256 {
            return err("perms length");
        }
        let mut perms = [0u8; 256];
        for (p, j) in perms.iter_mut().zip(perms_json) {
            *p = as_i64(j)? as u8;
        }
        Ok(crate::noise::GradientBase::from_parts(perms, bits("offsetX")?, bits("offsetY")?, bits("offsetZ")?))
    };
    Ok(match field(v, "type")?.as_str().unwrap_or("") {
        "perlin" => Arc::new(PerlinNoise { base: gradient()? }),
        "smeared" => Arc::new(SmearedPerlinNoise { perlin: PerlinNoise { base: gradient()? }, fudge_y_scale: bits("fudge")? }),
        "simplex" => Arc::new(SimplexNoise { base: gradient()? }),
        "stack" => {
            let mut stack = NoiseStack::default();
            for layer in field(v, "layers")?.as_array().ok_or_else(|| LoadError("layers".into()))? {
                let index = as_i64(field(layer, "noise")?)? as usize;
                let noise = built.get(index).cloned().ok_or_else(|| LoadError("layer noise order".into()))?;
                stack.layers.push(crate::noise::Layer {
                    noise,
                    frequency: f64::from_bits(as_i64(field(layer, "frequency")?)? as u64),
                    amplitude: f32::from_bits(as_i64(field(layer, "amplitude")?)? as u32),
                });
            }
            Arc::new(stack)
        }
        other => return err(format!("noise type {other}")),
    })
}

impl Graph {
    /// Parses the server's export. Unknown sampler classes are an error: the caller
    /// then leaves that dimension to vanilla.
    pub fn from_json(json: &Value) -> Result<Graph, LoadError> {
        let mut noises: Vec<Arc<dyn Noise>> = Vec::new();
        for v in field(json, "noises")?.as_array().ok_or_else(|| LoadError("noises".into()))? {
            let noise = parse_noise(v, &noises)?;
            noises.push(noise);
        }
        let mut nodes = Vec::new();
        let mut cache_count = 0;
        for v in field(json, "nodes")?.as_array().ok_or_else(|| LoadError("nodes".into()))? {
            let class = field(v, "c")?.as_str().unwrap_or("");
            let node = match class {
                "ConstantFunction$Sampler" => Node::Constant(f(v, "value")?),
                // Outside blending/structure chunks (which stay on vanilla) these always
                // use the fallback; an empty Beardifier also returns exactly 0.
                "ContextBoundSampler" => {
                    let key = field(field(v, "key")?, "key")?.as_str().unwrap_or("");
                    if key == "beardifier" {
                        nodes.push(Node::Beardifier);
                        continue;
                    }
                    if !matches!(key, "blend_alpha" | "blend_offset") {
                        return err(format!("context key {key}"));
                    }
                    nodes.push(nodes.get(n(v, "fallbackSampler")?).cloned().ok_or_else(|| LoadError("fallback order".into()))?);
                    continue;
                }
                "NoiseFunction$Sampler" => Node::Noise { noise: noise_ref(v, "noise")?, xz: d(v, "xzScale")?, y: d(v, "yScale")? },
                "NoiseFunction$ShiftedXzSampler" => Node::ShiftedXz {
                    sx: n(v, "shiftX")?,
                    sz: n(v, "shiftZ")?,
                    noise: noise_ref(v, "noise")?,
                    xz: d(v, "xzScale")?,
                    y: d(v, "yScale")?,
                },
                "NoiseFunction$ShiftedXyzSampler" => Node::ShiftedXyz {
                    sx: n(v, "shiftX")?,
                    sy: n(v, "shiftY")?,
                    sz: n(v, "shiftZ")?,
                    noise: noise_ref(v, "noise")?,
                    xz: d(v, "xzScale")?,
                    y: d(v, "yScale")?,
                },
                "ShiftNoiseFunction$ShiftB$1" => Node::ShiftB { noise: noise_ref(v, "noise")? },
                "GradientFunction$ClampedSampler" => Node::Gradient {
                    axis: axis(enum_name(v, "axis")?)?,
                    kind: GradientKind::Clamped {
                        from: i(v, "fromCoordinate")?,
                        min: i(v, "minCoordinate")?,
                        max: i(v, "maxCoordinate")?,
                    },
                    from_value: f(v, "fromValue")?,
                    factor: f(v, "coordinateFactor")?,
                },
                "GradientFunction$RepeatSampler" | "GradientFunction$MirroredRepeatSampler" => {
                    let from = i(v, "fromCoordinate")?;
                    let range = i(v, "coordinateRange")?;
                    Node::Gradient {
                        axis: axis(enum_name(v, "axis")?)?,
                        kind: if class.contains("Mirrored") {
                            GradientKind::Mirrored { from, range }
                        } else {
                            GradientKind::Repeat { from, range }
                        },
                        from_value: f(v, "fromValue")?,
                        factor: f(v, "coordinateFactor")?,
                    }
                }
                "DistanceToPointFunction$Sampler" => Node::DistanceToPoint {
                    x: i(v, "x")?,
                    y: i(v, "y")?,
                    z: i(v, "z")?,
                    metric: match enum_name(v, "metric")? {
                        "EUCLIDEAN" => Metric::Euclidean,
                        "EUCLIDEAN_SQUARED" => Metric::EuclideanSquared,
                        "MANHATTAN" => Metric::Manhattan,
                        "CHEBYSHEV" => Metric::Chebyshev,
                        other => return err(format!("metric {other}")),
                    },
                },
                "BinaryFunction$AddSampler" => Node::Add(n(v, "left")?, n(v, "right")?),
                "BinaryFunction$SubSampler" => Node::Sub(n(v, "left")?, n(v, "right")?),
                "BinaryFunction$MulSampler" => Node::Mul(n(v, "left")?, n(v, "right")?),
                "BinaryFunction$DivSampler" => Node::Div(n(v, "left")?, n(v, "right")?),
                "BinaryFunction$MinSampler" => Node::Min(n(v, "left")?, n(v, "right")?, f(v, "rightMinValue")?),
                "BinaryFunction$MaxSampler" => Node::Max(n(v, "left")?, n(v, "right")?, f(v, "rightMaxValue")?),
                "BinaryFunction$ConstAddSampler" => Node::ConstAdd(n(v, "left")?, f(v, "right")?),
                "BinaryFunction$ConstSubSampler" => Node::ConstSub(f(v, "left")?, n(v, "right")?),
                "BinaryFunction$ConstMulSampler" => Node::ConstMul(n(v, "left")?, f(v, "right")?),
                "BinaryFunction$ConstDivSampler" => Node::ConstDiv(f(v, "left")?, n(v, "right")?),
                "BinaryFunction$ConstMinSampler" => Node::ConstMin(n(v, "left")?, f(v, "right")?),
                "BinaryFunction$ConstMaxSampler" => Node::ConstMax(n(v, "left")?, f(v, "right")?),
                "UnaryFunction$AbsSampler" => Node::Unary(UnaryOp::Abs, n(v, "input")?),
                "UnaryFunction$SquareSampler" => Node::Unary(UnaryOp::Square, n(v, "input")?),
                "UnaryFunction$CubeSampler" => Node::Unary(UnaryOp::Cube, n(v, "input")?),
                "UnaryFunction$SqrtSampler" => Node::Unary(UnaryOp::Sqrt, n(v, "input")?),
                "UnaryFunction$LeakyReLUSampler" => Node::Unary(UnaryOp::LeakyRelu(f(v, "negativeFactor")?), n(v, "input")?),
                "UnaryFunction$ReciprocalSampler" => Node::Unary(UnaryOp::Reciprocal, n(v, "input")?),
                "UnaryFunction$NegateSampler" => Node::Unary(UnaryOp::Negate, n(v, "input")?),
                "UnaryFunction$SqueezeSampler" => Node::Unary(UnaryOp::Squeeze, n(v, "input")?),
                "UnaryFunction$LogSampler" => Node::Unary(UnaryOp::Log, n(v, "input")?),
                "UnaryFunction$SignSampler" => Node::Unary(UnaryOp::Sign, n(v, "input")?),
                "ClampFunction$Sampler" => Node::Clamp(n(v, "input")?, f(v, "min")?, f(v, "max")?),
                "LerpFunction$Sampler" => Node::Lerp(n(v, "alpha")?, n(v, "first")?, n(v, "second")?),
                "LerpFunction$ConstFirstSampler" => Node::LerpConstFirst(n(v, "alpha")?, f(v, "first")?, n(v, "second")?),
                "LerpFunction$ConstSecondSampler" => Node::LerpConstSecond(n(v, "alpha")?, n(v, "first")?, f(v, "second")?),
                "RangeChoiceFunction$Sampler" => Node::RangeChoice {
                    input: n(v, "input")?,
                    min: f(v, "minInclusive")?,
                    max: f(v, "maxExclusive")?,
                    in_range: n(v, "whenInRange")?,
                    out_range: n(v, "whenOutOfRange")?,
                },
                "RangeChoiceFunction$ConstSampler" => Node::RangeChoiceConst {
                    input: n(v, "input")?,
                    min: f(v, "minInclusive")?,
                    max: f(v, "maxExclusive")?,
                    in_range: f(v, "whenInRange")?,
                    out_range: f(v, "whenOutOfRange")?,
                },
                "IntervalSelectFunction$Sampler" => Node::IntervalSelect {
                    input: n(v, "input")?,
                    thresholds: floats(field(v, "thresholds")?)?,
                    samplers: field(field(v, "samplers")?, "nodes")?
                        .as_array()
                        .ok_or_else(|| LoadError("samplers".into()))?
                        .iter()
                        .map(|x| Ok(as_i64(x)? as usize))
                        .collect::<Result<_, LoadError>>()?,
                },
                "IntervalSelectFunction$SingleThresholdSampler" => Node::SingleThreshold {
                    input: n(v, "input")?,
                    threshold: f(v, "threshold")?,
                    below: n(v, "ifBelow")?,
                    above: n(v, "ifAbove")?,
                },
                "SplineFunction$Sampler" => Node::Spline {
                    spline: parse_spline(field(field(field(v, "sampler")?, "spline")?, "root")?)?,
                    coordinate_count: i(v, "coordinateCount")? as usize,
                },
                "RoundFunction$IntegerMultipleSampler" => Node::RoundInt(round_type(enum_name(v, "type")?)?, n(v, "input")?),
                "RoundFunction$Sampler" => Node::Round(round_type(enum_name(v, "type")?)?, n(v, "input")?, n(v, "multiple")?),
                "PowFunction$ConstBaseSampler" => Node::PowConstBase(d(v, "base")?, n(v, "exponent")?),
                "PowFunction$ConstExponentSampler" => Node::PowConstExp(n(v, "base")?, d(v, "exponent")?),
                "PowFunction$Sampler" => Node::Pow(n(v, "base")?, n(v, "exponent")?),
                "BlendDensityFunction$Sampler" => Node::BlendDensity(n(v, "input")?),
                "FindTopSurfaceFunction$Sampler" => Node::FindTopSurface {
                    density: n(v, "density")?,
                    upper: n(v, "upperBound")?,
                    lower: i(v, "lowerBound")?,
                    cell_height: i(v, "cellHeight")?,
                },
                "InterpolatedFunction$Sampler" => Node::Interpolated {
                    input: n(v, "input")?,
                    cell_xz: i(v, "cellSizeXz")?,
                    cell_y: i(v, "cellSizeY")?,
                    inv_xz: f(v, "cellSizeXzInv")?,
                    inv_y: f(v, "cellSizeYInv")?,
                },
                "SliceFunction$XSampler" => Node::SliceX(n(v, "input")?, i(v, "x")?),
                "SliceFunction$YSampler" => Node::SliceY(n(v, "input")?, i(v, "y")?),
                "SliceFunction$ZSampler" => Node::SliceZ(n(v, "input")?, i(v, "z")?),
                "SliceFunction$XzSampler" => Node::SliceXz(n(v, "input")?, i(v, "x")?, i(v, "z")?),
                "CachingDensitySampler" => {
                    let id = i(v, "id")? as usize;
                    cache_count = cache_count.max(id + 1);
                    Node::Cache { id, input: n(v, "input")? }
                }
                other => return err(format!("unsupported sampler {other}")),
            };
            nodes.push(node);
        }
        let mut roots = HashMap::new();
        for (name, id) in field(json, "roots")?.as_object().ok_or_else(|| LoadError("roots".into()))? {
            roots.insert(name.clone(), as_i64(id)? as usize);
        }
        let beard_kernel = match json.get("beard_kernel") {
            Some(v) => floats(v)?,
            None => Vec::new(),
        };
        if nodes.iter().any(|n| matches!(n, Node::Beardifier)) && beard_kernel.len() != 24 * 24 * 24 {
            return err("beard kernel missing");
        }
        Ok(Graph { nodes, noises, roots, cache_count, beard_kernel })
    }

    pub fn root(&self, name: &str) -> Option<NodeId> {
        self.roots.get(name).copied()
    }
}

/// `BlockPos.asLong`
#[inline]
pub fn pack_block_pos(x: i32, y: i32, z: i32) -> i64 {
    ((x as i64 & 0x3FF_FFFF) << 38) | (y as i64 & 0xFFF) | ((z as i64 & 0x3FF_FFFF) << 12)
}

/// One `SamplerContext.CacheCell`.
#[derive(Debug, Clone)]
pub struct CacheCell {
    pub volume: Option<DensityVolume>,
    pub buffer: Vec<f32>,
    pub value_key: i64,
    pub value: f32,
}

impl Default for CacheCell {
    fn default() -> Self {
        CacheCell { volume: None, buffer: Vec::new(), value_key: 0, value: f32::NAN }
    }
}

thread_local! {
    /// Scratch buffers kept per thread across chunks, so filling doesn't allocate.
    static POOL: std::cell::RefCell<Vec<Vec<f32>>> = const { std::cell::RefCell::new(Vec::new()) };
}

/// Returns buffers (e.g. cache cells of a finished chunk) to this thread's pool.
pub fn recycle(buffers: impl IntoIterator<Item = Vec<f32>>) {
    POOL.with(|p| {
        let mut pool = p.borrow_mut();
        for b in buffers {
            if b.capacity() > 0 && pool.len() < 64 {
                pool.push(b);
            }
        }
    });
}

impl Drop for Context<'_> {
    fn drop(&mut self) {
        let pool = std::mem::take(&mut self.pool);
        let cells: Vec<Vec<f32>> = self.cells.iter_mut().map(|c| std::mem::take(&mut c.buffer)).collect();
        recycle(pool.into_iter().chain(cells));
    }
}

/// `SamplerContext` with caches enabled, for one chunk.
pub struct Context<'g> {
    pub graph: &'g Graph,
    pub cells: Vec<CacheCell>,
    pool: Vec<Vec<f32>>,
    /// Inclusive nanoseconds and points per node when profiling (benchmarks only).
    pub profile: Option<Vec<(u64, u64)>>,
    /// This chunk's structure terrain adaptation.
    pub beard: Beard,
}

/// Java's `Math.round(float)`.
pub fn java_round(a: f32) -> i32 {
    let bits = a.to_bits() as i32;
    let biased_exp = (bits & 0x7F80_0000) >> 23;
    let shift = (24 - 2 + 127) - biased_exp;
    if shift & -32 == 0 {
        let mut r = (bits & 0x007F_FFFF) | 0x0080_0000;
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i32
    }
}

fn round_to_integer(input: f32, t: RoundType) -> f32 {
    match t {
        RoundType::Floor => (input as f64).floor() as f32,
        RoundType::Round => java_round(input) as f32,
        RoundType::Ceil => (input as f64).ceil() as f32,
        RoundType::Truncate => {
            if input > 0.0 {
                (input as f64).floor() as f32
            } else {
                (input as f64).ceil() as f32
            }
        }
    }
}

#[inline]
fn clamp(v: f32, min: f32, max: f32) -> f32 {
    // Mth.clamp: value < min ? min : Math.min(value, max)
    if v < min {
        min
    } else {
        java_min(v, max)
    }
}

/// `Math.min(float, float)`: NaN wins, -0.0 < 0.0.
#[inline]
fn java_min(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() != b.to_bits() {
        return if a.is_sign_negative() { a } else { b };
    }
    if a <= b {
        a
    } else {
        b
    }
}

/// `Math.max(float, float)`: NaN wins, 0.0 > -0.0.
#[inline]
fn java_max(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() != b.to_bits() {
        return if a.is_sign_negative() { b } else { a };
    }
    if a >= b {
        a
    } else {
        b
    }
}

/// `Math.signum(float)`
fn signum(v: f32) -> f32 {
    if v == 0.0 || v.is_nan() {
        v
    } else if v > 0.0 {
        1.0
    } else {
        -1.0
    }
}

fn unary(op: UnaryOp, v: f32) -> f32 {
    match op {
        UnaryOp::Abs => v.abs(),
        UnaryOp::Square => v * v,
        UnaryOp::Cube => v * v * v,
        UnaryOp::Sqrt => (v as f64).sqrt() as f32,
        UnaryOp::LeakyRelu(factor) => {
            if v > 0.0 {
                v
            } else {
                v * factor
            }
        }
        UnaryOp::Reciprocal => 1.0 / v,
        UnaryOp::Negate => -v,
        UnaryOp::Squeeze => {
            let c = clamp(v, -1.0, 1.0);
            c / 2.0 - c * c * c / 24.0
        }
        UnaryOp::Log => jmath::log(v as f64) as f32,
        UnaryOp::Sign => signum(v),
    }
}

/// `unary` over a slice, with the operation chosen once so each loop can vectorize.
fn unary_slice(op: UnaryOp, out: &mut [f32]) {
    match op {
        UnaryOp::Square => out.iter_mut().for_each(|v| *v = unary(UnaryOp::Square, *v)),
        UnaryOp::Cube => out.iter_mut().for_each(|v| *v = unary(UnaryOp::Cube, *v)),
        UnaryOp::Squeeze => out.iter_mut().for_each(|v| *v = unary(UnaryOp::Squeeze, *v)),
        UnaryOp::Abs => out.iter_mut().for_each(|v| *v = unary(UnaryOp::Abs, *v)),
        UnaryOp::Negate => out.iter_mut().for_each(|v| *v = unary(UnaryOp::Negate, *v)),
        other => out.iter_mut().for_each(|v| *v = unary(other, *v)),
    }
}

fn gradient_compute(kind: GradientKind, from_value: f32, factor: f32, coordinate: i32) -> f32 {
    match kind {
        GradientKind::Clamped { from, min, max } => {
            let c = if coordinate < min { min } else { coordinate.min(max) };
            from_value + (c - from) as f32 * factor
        }
        GradientKind::Repeat { from, range } => from_value + (coordinate - from).rem_euclid(range) as f32 * factor,
        GradientKind::Mirrored { from, range } => {
            let relative = coordinate - from;
            let tile = relative.div_euclid(range);
            let local = relative - tile * range;
            if tile & 1 == 0 {
                from_value + local as f32 * factor
            } else {
                from_value + (range - local) as f32 * factor
            }
        }
    }
}

fn metric(m: Metric, dx: f32, dy: f32, dz: f32) -> f32 {
    match m {
        // Mth.length(float, float, float) = (float) Math.sqrt(lengthSquared)
        Metric::Euclidean => ((dx * dx + dy * dy + dz * dz) as f64).sqrt() as f32,
        Metric::EuclideanSquared => dx * dx + dy * dy + dz * dz,
        Metric::Manhattan => dx.abs() + dy.abs() + dz.abs(),
        Metric::Chebyshev => java_max(java_max(dx.abs(), dy.abs()), dz.abs()),
    }
}

/// `Mth.binarySearch(0, len, i -> input < locations[i]) - 1`
fn find_interval_start(locations: &[f32], input: f32) -> i32 {
    let (mut from, mut len) = (0usize, locations.len());
    while len > 0 {
        let half = len / 2;
        let middle = from + half;
        if input < locations[middle] {
            len = half;
        } else {
            from = middle + 1;
            len -= half + 1;
        }
    }
    from as i32 - 1
}

fn linear_extend(input: f32, locations: &[f32], value: f32, derivatives: &[f32], index: usize) -> f32 {
    let derivative = derivatives[index];
    if derivative == 0.0 {
        value
    } else {
        value + derivative * (input - locations[index])
    }
}

/// Spline coordinate source: lazily computed volume buffers, or cached point values.
enum SplineInput {
    Buffer { volume: DensityVolume, buffers: Vec<Option<Vec<f32>>>, index: usize },
    Point { x: i32, y: i32, z: i32, cached: Vec<f32> },
}

impl<'g> Context<'g> {
    pub fn new(graph: &'g Graph) -> Context<'g> {
        let pool = POOL.with(|p| std::mem::take(&mut *p.borrow_mut()));
        Context { graph, cells: vec![CacheCell::default(); graph.cache_count], pool, profile: None, beard: Beard::default() }
    }

    /// A scratch buffer of `len` values. Like vanilla's pooled buffers its contents are
    /// stale; every sampler writes its whole output.
    fn acquire(&mut self, len: usize) -> Vec<f32> {
        let mut buffer = self.pool.pop().unwrap_or_default();
        if buffer.len() != len {
            buffer.resize(len, 0.0);
        }
        buffer
    }

    fn release(&mut self, buffer: Vec<f32>) {
        self.pool.push(buffer);
    }

    /// `DensitySampler.Bound.sampleVolume(volume)` into a new buffer.
    pub fn sample_volume_new(&mut self, id: NodeId, volume: &DensityVolume) -> Vec<f32> {
        let mut out = self.acquire(volume.size());
        self.sample_volume(id, &mut out, volume);
        out
    }

    pub fn sample_volume(&mut self, id: NodeId, out: &mut [f32], vol: &DensityVolume) {
        if self.profile.is_some() {
            let start = std::time::Instant::now();
            self.sample_volume_inner(id, out, vol);
            let p = &mut self.profile.as_mut().unwrap()[id];
            p.0 += start.elapsed().as_nanos() as u64;
            p.1 += out.len() as u64;
        } else {
            self.sample_volume_inner(id, out, vol);
        }
    }

    fn sample_volume_inner(&mut self, id: NodeId, out: &mut [f32], vol: &DensityVolume) {
        let g = self.graph;
        match &g.nodes[id] {
            Node::Constant(v) => out.fill(*v),
            Node::Noise { noise, xz, y } => {
                out.fill(0.0);
                g.noises[*noise].add_to_volume(out, vol, *xz, *y, 1.0);
            }
            Node::ShiftedXz { sx, sz, noise, xz, y } => {
                self.sample_volume(*sx, out, vol);
                let mut bz = self.acquire(out.len());
                self.sample_volume(*sz, &mut bz, vol);
                let noise = &g.noises[*noise];
                let mut index = 0;
                for iz in 0..vol.size_z {
                    let base_z = vol.block_z(iz) as f64 * xz;
                    for ix in 0..vol.size_x {
                        let base_x = vol.block_x(ix) as f64 * xz;
                        for iy in 0..vol.size_y {
                            let nx = base_x + out[index] as f64;
                            let ny = vol.block_y(iy) as f64 * y;
                            let nz = base_z + bz[index] as f64;
                            out[index] = noise.get3(nx, ny, nz);
                            index += 1;
                        }
                    }
                }
                self.release(bz);
            }
            Node::ShiftedXyz { sx, sy, sz, noise, xz, y } => {
                self.sample_volume(*sx, out, vol);
                let mut by = self.acquire(out.len());
                self.sample_volume(*sy, &mut by, vol);
                let mut bz = self.acquire(out.len());
                self.sample_volume(*sz, &mut bz, vol);
                let noise = &g.noises[*noise];
                let mut index = 0;
                for iz in 0..vol.size_z {
                    let base_z = vol.block_z(iz) as f64 * xz;
                    for ix in 0..vol.size_x {
                        let base_x = vol.block_x(ix) as f64 * xz;
                        for iy in 0..vol.size_y {
                            let nx = base_x + out[index] as f64;
                            let ny = vol.block_y(iy) as f64 * y + by[index] as f64;
                            let nz = base_z + bz[index] as f64;
                            out[index] = noise.get3(nx, ny, nz);
                            index += 1;
                        }
                    }
                }
                self.release(bz);
                self.release(by);
            }
            Node::ShiftB { noise } => {
                let t = DensityVolume::new(
                    [vol.size_z, vol.size_x, 1],
                    [vol.min_z, vol.min_x, 0],
                    [vol.step_z, vol.step_x, 1],
                );
                let mut tb = self.acquire(t.size());
                tb.fill(0.0);
                g.noises[*noise].add_to_volume(&mut tb, &t, 0.25, 0.25, 4.0);
                for iz in 0..vol.size_z {
                    for ix in 0..vol.size_x {
                        let value = tb[t.index_unchecked(iz, ix, 0)];
                        let start = vol.index_unchecked(ix, 0, iz);
                        out[start..start + vol.size_y as usize].fill(value);
                    }
                }
                self.release(tb);
            }
            Node::Gradient { axis, kind, from_value, factor } => {
                let compute = |c| gradient_compute(*kind, *from_value, *factor, c);
                match axis {
                    Axis::X => {
                        for ix in 0..vol.size_x {
                            let value = compute(vol.block_x(ix));
                            for iz in 0..vol.size_z {
                                let start = vol.index_unchecked(ix, 0, iz);
                                out[start..start + vol.size_y as usize].fill(value);
                            }
                        }
                    }
                    Axis::Y => {
                        for iy in 0..vol.size_y {
                            let value = compute(vol.block_y(iy));
                            for iz in 0..vol.size_z {
                                for ix in 0..vol.size_x {
                                    out[vol.index_unchecked(ix, iy, iz)] = value;
                                }
                            }
                        }
                    }
                    Axis::Z => {
                        for iz in 0..vol.size_z {
                            let value = compute(vol.block_z(iz));
                            let start = vol.index_unchecked(0, 0, iz);
                            out[start..start + (vol.size_x * vol.size_y) as usize].fill(value);
                        }
                    }
                }
            }
            Node::DistanceToPoint { x, y, z, metric: m } => {
                let mut index = 0;
                for iz in 0..vol.size_z {
                    let bz = vol.block_z(iz);
                    for ix in 0..vol.size_x {
                        let bx = vol.block_x(ix);
                        for iy in 0..vol.size_y {
                            let by = vol.block_y(iy);
                            out[index] = metric(*m, (x - bx) as f32, (y - by) as f32, (z - bz) as f32);
                            index += 1;
                        }
                    }
                }
            }
            Node::Add(l, r) => self.binary_volume(*l, *r, out, vol, |a, b| a + b),
            Node::Sub(l, r) => self.binary_volume(*l, *r, out, vol, |a, b| a + -b),
            Node::Mul(l, r) => self.binary_volume(*l, *r, out, vol, |a, b| a * b),
            Node::Div(l, r) => self.binary_volume(*l, *r, out, vol, |a, b| a / b),
            Node::Min(l, r, _) => self.binary_volume(*l, *r, out, vol, |a, b| if b < a { b } else { a }),
            Node::Max(l, r, _) => self.binary_volume(*l, *r, out, vol, |a, b| if b > a { b } else { a }),
            Node::ConstAdd(l, c) => {
                self.sample_volume(*l, out, vol);
                out.iter_mut().for_each(|v| *v += *c);
            }
            Node::ConstSub(c, r) => {
                self.sample_volume(*r, out, vol);
                out.iter_mut().for_each(|v| *v = *c - *v);
            }
            Node::ConstMul(l, c) => {
                self.sample_volume(*l, out, vol);
                out.iter_mut().for_each(|v| *v *= *c);
            }
            Node::ConstDiv(c, r) => {
                self.sample_volume(*r, out, vol);
                out.iter_mut().for_each(|v| *v = *c / *v);
            }
            Node::ConstMin(l, c) => {
                self.sample_volume(*l, out, vol);
                out.iter_mut().for_each(|v| {
                    if *c < *v {
                        *v = *c
                    }
                });
            }
            Node::ConstMax(l, c) => {
                self.sample_volume(*l, out, vol);
                out.iter_mut().for_each(|v| {
                    if *c > *v {
                        *v = *c
                    }
                });
            }
            Node::Unary(op, input) => {
                self.sample_volume(*input, out, vol);
                unary_slice(*op, out);
            }
            Node::Clamp(input, min, max) => {
                self.sample_volume(*input, out, vol);
                out.iter_mut().for_each(|v| *v = clamp(*v, *min, *max));
            }
            Node::Lerp(a, first, second) => {
                self.sample_volume(*a, out, vol);
                let mut fb = self.acquire(out.len());
                self.sample_volume(*first, &mut fb, vol);
                let mut sb = self.acquire(out.len());
                self.sample_volume(*second, &mut sb, vol);
                for i in 0..out.len() {
                    let alpha = out[i];
                    out[i] = if alpha == 0.0 {
                        fb[i]
                    } else if alpha == 1.0 {
                        sb[i]
                    } else {
                        lerp(alpha, fb[i], sb[i])
                    };
                }
                self.release(sb);
                self.release(fb);
            }
            Node::LerpConstFirst(a, first, second) => {
                self.sample_volume(*a, out, vol);
                let mut sb = self.acquire(out.len());
                self.sample_volume(*second, &mut sb, vol);
                for i in 0..out.len() {
                    let alpha = out[i];
                    out[i] = if alpha == 0.0 {
                        *first
                    } else if alpha == 1.0 {
                        sb[i]
                    } else {
                        lerp(alpha, *first, sb[i])
                    };
                }
                self.release(sb);
            }
            Node::LerpConstSecond(a, first, second) => {
                self.sample_volume(*a, out, vol);
                let mut fb = self.acquire(out.len());
                self.sample_volume(*first, &mut fb, vol);
                for i in 0..out.len() {
                    let alpha = out[i];
                    out[i] = if alpha == 0.0 {
                        fb[i]
                    } else if alpha == 1.0 {
                        *second
                    } else {
                        lerp(alpha, fb[i], *second)
                    };
                }
                self.release(fb);
            }
            Node::RangeChoice { input, min, max, in_range, out_range } => {
                self.sample_volume(*in_range, out, vol);
                let mut ib = self.acquire(out.len());
                self.sample_volume(*input, &mut ib, vol);
                let mut ob = self.acquire(out.len());
                self.sample_volume(*out_range, &mut ob, vol);
                for i in 0..out.len() {
                    let v = ib[i];
                    if !(v >= *min) || !(v < *max) {
                        out[i] = ob[i];
                    }
                }
                self.release(ob);
                self.release(ib);
            }
            Node::RangeChoiceConst { input, min, max, in_range, out_range } => {
                self.sample_volume(*input, out, vol);
                out.iter_mut().for_each(|v| *v = if *v >= *min && *v < *max { *in_range } else { *out_range });
            }
            Node::IntervalSelect { input, thresholds, samplers } => {
                self.sample_volume(*input, out, vol);
                let mut buffers = Vec::with_capacity(samplers.len());
                for s in samplers {
                    let mut b = self.acquire(out.len());
                    self.sample_volume(*s, &mut b, vol);
                    buffers.push(b);
                }
                for i in 0..out.len() {
                    let index = select_index(thresholds, samplers.len(), out[i]);
                    out[i] = buffers[index][i];
                }
                for b in buffers {
                    self.release(b);
                }
            }
            Node::SingleThreshold { input, threshold, below, above } => {
                self.sample_volume(*input, out, vol);
                let mut bb = self.acquire(out.len());
                self.sample_volume(*below, &mut bb, vol);
                let mut ab = self.acquire(out.len());
                self.sample_volume(*above, &mut ab, vol);
                for i in 0..out.len() {
                    out[i] = if out[i] < *threshold { bb[i] } else { ab[i] };
                }
                self.release(ab);
                self.release(bb);
            }
            Node::Spline { spline, coordinate_count } => {
                let mut input =
                    SplineInput::Buffer { volume: *vol, buffers: vec![None; *coordinate_count], index: 0 };
                for (i, slot) in out.iter_mut().enumerate() {
                    if let SplineInput::Buffer { index, .. } = &mut input {
                        *index = i;
                    }
                    *slot = self.spline(spline, &mut input);
                }
                if let SplineInput::Buffer { buffers, .. } = input {
                    for b in buffers.into_iter().flatten() {
                        self.release(b);
                    }
                }
            }
            Node::RoundInt(t, input) => {
                self.sample_volume(*input, out, vol);
                out.iter_mut().for_each(|v| *v = round_to_integer(*v, *t));
            }
            Node::Round(t, input, multiple) => {
                self.sample_volume(*input, out, vol);
                let mut mb = self.acquire(out.len());
                self.sample_volume(*multiple, &mut mb, vol);
                for i in 0..out.len() {
                    out[i] = round_multiple(*t, out[i], mb[i]);
                }
                self.release(mb);
            }
            Node::PowConstBase(base, exponent) => {
                self.sample_volume(*exponent, out, vol);
                out.iter_mut().for_each(|v| *v = jmath::pow(*base, *v as f64) as f32);
            }
            Node::PowConstExp(base, exponent) => {
                self.sample_volume(*base, out, vol);
                out.iter_mut().for_each(|v| *v = jmath::pow(*v as f64, *exponent) as f32);
            }
            Node::Pow(base, exponent) => self.binary_volume(*base, *exponent, out, vol, |a, b| jmath::pow(a as f64, b as f64) as f32),
            Node::BlendDensity(input) => self.sample_volume(*input, out, vol),
            Node::FindTopSurface { density, upper, lower, cell_height } => {
                assert_eq!(vol.size_y, 1, "Cannot sample with sizeY={}", vol.size_y);
                self.sample_volume(*upper, out, vol);
                let mut index = 0;
                for iz in 0..vol.size_z {
                    let bz = vol.block_z(iz);
                    for ix in 0..vol.size_x {
                        let bx = vol.block_x(ix);
                        out[index] = self.find_surface(*density, *lower, *cell_height, bx, bz, out[index]) as f32;
                        index += 1;
                    }
                }
            }
            Node::Interpolated { input, cell_xz, cell_y, inv_xz, inv_y } => {
                let (cx, cy) = (*cell_xz, *cell_y);
                if (vol.step_x == cx || vol.size_x == 1)
                    && (vol.step_y == cy || vol.size_y == 1)
                    && (vol.step_z == cx || vol.size_z == 1)
                    && vol.min_x.rem_euclid(cx) == 0
                    && vol.min_y.rem_euclid(cy) == 0
                    && vol.min_z.rem_euclid(cx) == 0
                {
                    self.sample_volume(*input, out, vol);
                } else if vol.step_x == 1 && vol.step_y == 1 && vol.step_z == 1 {
                    self.interpolate_block_step(*input, cx, cy, *inv_xz, *inv_y, out, vol);
                } else {
                    let block = DensityVolume::new(
                        [vol.size_x * vol.step_x, vol.size_y * vol.step_y, vol.size_z * vol.step_z],
                        [vol.min_x, vol.min_y, vol.min_z],
                        [1, 1, 1],
                    );
                    let mut bb = self.acquire(block.size());
                    self.interpolate_block_step(*input, cx, cy, *inv_xz, *inv_y, &mut bb, &block);
                    for iz in 0..vol.size_z {
                        for ix in 0..vol.size_x {
                            for iy in 0..vol.size_y {
                                out[vol.index_unchecked(ix, iy, iz)] =
                                    bb[block.index_unchecked(ix * vol.step_x, iy * vol.step_y, iz * vol.step_z)];
                            }
                        }
                    }
                    self.release(bb);
                }
            }
            Node::SliceX(input, x) => {
                if vol.size_x == 1 && vol.min_x == *x {
                    self.sample_volume(*input, out, vol);
                } else {
                    let iv = DensityVolume::new(
                        [1, vol.size_y, vol.size_z],
                        [*x, vol.min_y, vol.min_z],
                        [vol.step_x, vol.step_y, vol.step_z],
                    );
                    let mut ib = self.acquire(iv.size());
                    self.sample_volume(*input, &mut ib, &iv);
                    let mut index = 0;
                    for iz in 0..vol.size_z {
                        for _ in 0..vol.size_x {
                            for iy in 0..vol.size_y {
                                out[index] = ib[iv.index_unchecked(0, iy, iz)];
                                index += 1;
                            }
                        }
                    }
                    self.release(ib);
                }
            }
            Node::SliceY(input, y) => {
                if vol.size_y == 1 && vol.min_y == *y {
                    self.sample_volume(*input, out, vol);
                } else {
                    let iv = DensityVolume::new(
                        [vol.size_x, 1, vol.size_z],
                        [vol.min_x, *y, vol.min_z],
                        [vol.step_x, vol.step_y, vol.step_z],
                    );
                    let mut ib = self.acquire(iv.size());
                    self.sample_volume(*input, &mut ib, &iv);
                    for iz in 0..vol.size_z {
                        for ix in 0..vol.size_x {
                            let value = ib[iv.index_unchecked(ix, 0, iz)];
                            let start = vol.index_unchecked(ix, 0, iz);
                            out[start..start + vol.size_y as usize].fill(value);
                        }
                    }
                    self.release(ib);
                }
            }
            Node::SliceZ(input, z) => {
                if vol.size_z == 1 && vol.min_z == *z {
                    self.sample_volume(*input, out, vol);
                } else {
                    let iv = DensityVolume::new(
                        [vol.size_x, vol.size_y, 1],
                        [vol.min_x, vol.min_y, *z],
                        [vol.step_x, vol.step_y, vol.step_z],
                    );
                    let mut ib = self.acquire(iv.size());
                    self.sample_volume(*input, &mut ib, &iv);
                    let mut index = 0;
                    for _ in 0..vol.size_z {
                        for ix in 0..vol.size_x {
                            for iy in 0..vol.size_y {
                                out[index] = ib[iv.index_unchecked(ix, iy, 0)];
                                index += 1;
                            }
                        }
                    }
                    self.release(ib);
                }
            }
            Node::SliceXz(input, x, z) => {
                if vol.size_x == 1 && vol.size_z == 1 && vol.min_x == *x && vol.min_z == *z {
                    self.sample_volume(*input, out, vol);
                } else {
                    let iv = DensityVolume::new(
                        [1, vol.size_y, 1],
                        [*x, vol.min_y, *z],
                        [vol.step_x, vol.step_y, vol.step_z],
                    );
                    let mut ib = self.acquire(iv.size());
                    self.sample_volume(*input, &mut ib, &iv);
                    let step = vol.size_y as usize;
                    for iy in 0..vol.size_y {
                        let value = ib[iv.index_unchecked(0, iy, 0)];
                        let mut index = vol.index_unchecked(0, iy, 0);
                        for _ in 0..vol.size_z {
                            for _ in 0..vol.size_x {
                                out[index] = value;
                                index += step;
                            }
                        }
                    }
                    self.release(ib);
                }
            }
            Node::Beardifier => {
                out.fill(0.0);
                if let Some((min, max)) = self.beard.affected {
                    let intersects = max[0] >= vol.min_x
                        && min[0] <= vol.max_block_x()
                        && max[2] >= vol.min_z
                        && min[2] <= vol.max_block_z()
                        && max[1] >= vol.min_y
                        && min[1] <= vol.max_block_y();
                    if intersects {
                        let lo_x = 0.max(min[0] - vol.min_x).div_euclid(vol.step_x);
                        let lo_y = 0.max(min[1] - vol.min_y).div_euclid(vol.step_y);
                        let lo_z = 0.max(min[2] - vol.min_z).div_euclid(vol.step_z);
                        let hi_x = (vol.size_x - 1).min((max[0] - vol.min_x).div_euclid(vol.step_x));
                        let hi_y = (vol.size_y - 1).min((max[1] - vol.min_y).div_euclid(vol.step_y));
                        let hi_z = (vol.size_z - 1).min((max[2] - vol.min_z).div_euclid(vol.step_z));
                        for z in lo_z..=hi_z {
                            let bz = vol.block_z(z);
                            for x in lo_x..=hi_x {
                                let bx = vol.block_x(x);
                                for y in lo_y..=hi_y {
                                    out[vol.index_unchecked(x, y, z)] = beard_value(&self.beard, &g.beard_kernel, bx, vol.block_y(y), bz);
                                }
                            }
                        }
                    }
                }
            }
            Node::Cache { id, input } => {
                let id = *id;
                if self.cells[id].volume.as_ref() != Some(vol) {
                    let old = std::mem::take(&mut self.cells[id].buffer);
                    if old.capacity() > 0 {
                        self.release(old);
                    }
                    let mut buffer = self.acquire(vol.size());
                    self.cells[id].volume = Some(*vol);
                    self.sample_volume(*input, &mut buffer, vol);
                    self.cells[id].buffer = buffer;
                }
                out.copy_from_slice(&self.cells[id].buffer);
            }
        }
    }

    fn binary_volume(&mut self, l: NodeId, r: NodeId, out: &mut [f32], vol: &DensityVolume, op: impl Fn(f32, f32) -> f32) {
        self.sample_volume(l, out, vol);
        let mut rb = self.acquire(out.len());
        self.sample_volume(r, &mut rb, vol);
        for (o, b) in out.iter_mut().zip(&rb) {
            *o = op(*o, *b);
        }
        self.release(rb);
    }

    pub fn sample_value(&mut self, id: NodeId, x: i32, y: i32, z: i32) -> f32 {
        let g = self.graph;
        match &g.nodes[id] {
            Node::Constant(v) => *v,
            Node::Noise { noise, xz, y: ys } => g.noises[*noise].get3(x as f64 * xz, y as f64 * ys, z as f64 * xz),
            Node::ShiftedXz { sx, sz, noise, xz, y: ys } => {
                let nx = x as f64 * xz + self.sample_value(*sx, x, y, z) as f64;
                let ny = y as f64 * ys;
                let nz = z as f64 * xz + self.sample_value(*sz, x, y, z) as f64;
                g.noises[*noise].get3(nx, ny, nz)
            }
            Node::ShiftedXyz { sx, sy, sz, noise, xz, y: ys } => {
                let nx = x as f64 * xz + self.sample_value(*sx, x, y, z) as f64;
                let ny = y as f64 * ys + self.sample_value(*sy, x, y, z) as f64;
                let nz = z as f64 * xz + self.sample_value(*sz, x, y, z) as f64;
                g.noises[*noise].get3(nx, ny, nz)
            }
            Node::ShiftB { noise } => g.noises[*noise].get3(z as f64 * 0.25, x as f64 * 0.25, 0.0) * 4.0,
            Node::Gradient { axis, kind, from_value, factor } => gradient_compute(*kind, *from_value, *factor, axis.choose(x, y, z)),
            Node::DistanceToPoint { x: px, y: py, z: pz, metric: m } => metric(*m, (px - x) as f32, (py - y) as f32, (pz - z) as f32),
            Node::Add(l, r) => self.sample_value(*l, x, y, z) + self.sample_value(*r, x, y, z),
            Node::Sub(l, r) => self.sample_value(*l, x, y, z) - self.sample_value(*r, x, y, z),
            Node::Mul(l, r) => {
                let left = self.sample_value(*l, x, y, z);
                if left == 0.0 {
                    0.0
                } else {
                    left * self.sample_value(*r, x, y, z)
                }
            }
            Node::Div(l, r) => {
                let left = self.sample_value(*l, x, y, z);
                if left == 0.0 {
                    0.0
                } else {
                    left / self.sample_value(*r, x, y, z)
                }
            }
            Node::Min(l, r, right_min) => {
                let left = self.sample_value(*l, x, y, z);
                if left <= *right_min {
                    left
                } else {
                    java_min(left, self.sample_value(*r, x, y, z))
                }
            }
            Node::Max(l, r, right_max) => {
                let left = self.sample_value(*l, x, y, z);
                if left >= *right_max {
                    left
                } else {
                    java_max(left, self.sample_value(*r, x, y, z))
                }
            }
            Node::ConstAdd(l, c) => self.sample_value(*l, x, y, z) + c,
            Node::ConstSub(c, r) => c - self.sample_value(*r, x, y, z),
            Node::ConstMul(l, c) => self.sample_value(*l, x, y, z) * c,
            Node::ConstDiv(c, r) => c / self.sample_value(*r, x, y, z),
            Node::ConstMin(l, c) => java_min(self.sample_value(*l, x, y, z), *c),
            Node::ConstMax(l, c) => java_max(self.sample_value(*l, x, y, z), *c),
            Node::Unary(op, input) => unary(*op, self.sample_value(*input, x, y, z)),
            Node::Clamp(input, min, max) => clamp(self.sample_value(*input, x, y, z), *min, *max),
            Node::Lerp(a, first, second) => {
                let alpha = self.sample_value(*a, x, y, z);
                if alpha == 0.0 {
                    self.sample_value(*first, x, y, z)
                } else if alpha == 1.0 {
                    self.sample_value(*second, x, y, z)
                } else {
                    let f = self.sample_value(*first, x, y, z);
                    let s = self.sample_value(*second, x, y, z);
                    lerp(alpha, f, s)
                }
            }
            Node::LerpConstFirst(a, first, second) => {
                let alpha = self.sample_value(*a, x, y, z);
                if alpha == 0.0 {
                    *first
                } else if alpha == 1.0 {
                    self.sample_value(*second, x, y, z)
                } else {
                    lerp(alpha, *first, self.sample_value(*second, x, y, z))
                }
            }
            Node::LerpConstSecond(a, first, second) => {
                let alpha = self.sample_value(*a, x, y, z);
                if alpha == 0.0 {
                    self.sample_value(*first, x, y, z)
                } else if alpha == 1.0 {
                    *second
                } else {
                    lerp(alpha, self.sample_value(*first, x, y, z), *second)
                }
            }
            Node::RangeChoice { input, min, max, in_range, out_range } => {
                let v = self.sample_value(*input, x, y, z);
                if v >= *min && v < *max {
                    self.sample_value(*in_range, x, y, z)
                } else {
                    self.sample_value(*out_range, x, y, z)
                }
            }
            Node::RangeChoiceConst { input, min, max, in_range, out_range } => {
                let v = self.sample_value(*input, x, y, z);
                if v >= *min && v < *max {
                    *in_range
                } else {
                    *out_range
                }
            }
            Node::IntervalSelect { input, thresholds, samplers } => {
                let v = self.sample_value(*input, x, y, z);
                let index = select_index(thresholds, samplers.len(), v);
                self.sample_value(samplers[index], x, y, z)
            }
            Node::SingleThreshold { input, threshold, below, above } => {
                let v = self.sample_value(*input, x, y, z);
                if v < *threshold {
                    self.sample_value(*below, x, y, z)
                } else {
                    self.sample_value(*above, x, y, z)
                }
            }
            Node::Spline { spline, coordinate_count } => {
                let mut input = SplineInput::Point { x, y, z, cached: vec![f32::NAN; *coordinate_count] };
                self.spline(spline, &mut input)
            }
            Node::RoundInt(t, input) => round_to_integer(self.sample_value(*input, x, y, z), *t),
            Node::Round(t, input, multiple) => {
                let v = self.sample_value(*input, x, y, z);
                let m = self.sample_value(*multiple, x, y, z);
                round_multiple(*t, v, m)
            }
            Node::PowConstBase(base, exponent) => jmath::pow(*base, self.sample_value(*exponent, x, y, z) as f64) as f32,
            Node::PowConstExp(base, exponent) => jmath::pow(self.sample_value(*base, x, y, z) as f64, *exponent) as f32,
            Node::Pow(base, exponent) => {
                let b = self.sample_value(*base, x, y, z);
                let e = self.sample_value(*exponent, x, y, z);
                jmath::pow(b as f64, e as f64) as f32
            }
            Node::BlendDensity(input) => self.sample_value(*input, x, y, z),
            Node::FindTopSurface { density, upper, lower, cell_height } => {
                let upper_bound = self.sample_value(*upper, x, y, z);
                self.find_surface(*density, *lower, *cell_height, x, z, upper_bound) as f32
            }
            Node::Interpolated { input, cell_xz, cell_y, .. } => {
                let (cx, cy) = (*cell_xz, *cell_y);
                let (xi, yi, zi) = (x.rem_euclid(cx), y.rem_euclid(cy), z.rem_euclid(cx));
                if xi == 0 && yi == 0 && zi == 0 {
                    return self.sample_value(*input, x, y, z);
                }
                let v = DensityVolume::new([2, 2, 2], [x - xi, y - yi, z - zi], [cx, cy, cx]);
                let b = self.sample_volume_new(*input, &v);
                let result = lerp3(
                    xi as f32 / cx as f32,
                    yi as f32 / cy as f32,
                    zi as f32 / cx as f32,
                    b[v.index_unchecked(0, 0, 0)],
                    b[v.index_unchecked(1, 0, 0)],
                    b[v.index_unchecked(0, 1, 0)],
                    b[v.index_unchecked(1, 1, 0)],
                    b[v.index_unchecked(0, 0, 1)],
                    b[v.index_unchecked(1, 0, 1)],
                    b[v.index_unchecked(0, 1, 1)],
                    b[v.index_unchecked(1, 1, 1)],
                );
                self.release(b);
                result
            }
            Node::SliceX(input, sx) => self.sample_value(*input, *sx, y, z),
            Node::SliceY(input, sy) => self.sample_value(*input, x, *sy, z),
            Node::SliceZ(input, sz) => self.sample_value(*input, x, y, *sz),
            Node::SliceXz(input, sx, sz) => self.sample_value(*input, *sx, y, *sz),
            Node::Beardifier => match self.beard.affected {
                Some((min, max))
                    if x >= min[0] && x <= max[0] && z >= min[2] && z <= max[2] && y >= min[1] && y <= max[1] =>
                {
                    beard_value(&self.beard, &g.beard_kernel, x, y, z)
                }
                _ => 0.0,
            },
            Node::Cache { id, input } => {
                let id = *id;
                let key = pack_block_pos(x, y, z);
                let cell = &self.cells[id];
                if cell.value_key == key && !cell.value.is_nan() {
                    return cell.value;
                }
                if let Some(volume) = cell.volume {
                    if let Some(index) = volume.index_of_block(x, y, z) {
                        return cell.buffer[index];
                    }
                }
                let value = self.sample_value(*input, x, y, z);
                let cell = &mut self.cells[id];
                cell.value_key = key;
                cell.value = value;
                value
            }
        }
    }

    fn find_surface(&mut self, density: NodeId, lower: i32, cell_height: i32, x: i32, z: i32, upper_bound: f32) -> i32 {
        let top_y = ((upper_bound / cell_height as f32) as f64).floor() as i32 * cell_height;
        if top_y <= lower {
            return lower;
        }
        let mut probe = top_y;
        while probe >= lower {
            if self.sample_value(density, x, probe, z) > 0.0 {
                return probe;
            }
            probe -= cell_height;
        }
        lower
    }

    fn spline_coordinate(&mut self, sampler: NodeId, index: usize, input: &mut SplineInput) -> f32 {
        match input {
            SplineInput::Buffer { volume, buffers, index: point } => {
                if buffers[index].is_none() {
                    let volume = *volume;
                    let b = self.sample_volume_new(sampler, &volume);
                    buffers[index] = Some(b);
                }
                buffers[index].as_ref().unwrap()[*point]
            }
            SplineInput::Point { x, y, z, cached } => {
                let c = cached[index];
                if !c.is_nan() {
                    return c;
                }
                let (x, y, z) = (*x, *y, *z);
                let value = self.sample_value(sampler, x, y, z);
                if let SplineInput::Point { cached, .. } = input {
                    cached[index] = value;
                }
                value
            }
        }
    }

    /// `CubicSpline.Multipoint.sample`, in vanilla's evaluation order.
    fn spline(&mut self, spline: &Spline, input: &mut SplineInput) -> f32 {
        match spline {
            Spline::Constant(v) => *v,
            Spline::Multipoint { sampler, index, locations, derivatives, values } => {
                let x = self.spline_coordinate(*sampler, *index, input);
                let start = find_interval_start(locations, x);
                let last = locations.len() - 1;
                if start < 0 {
                    let v = self.spline(&values[0], input);
                    return linear_extend(x, locations, v, derivatives, 0);
                }
                let start = start as usize;
                if start == last {
                    let v = self.spline(&values[last], input);
                    return linear_extend(x, locations, v, derivatives, last);
                }
                let x1 = locations[start];
                let x2 = locations[start + 1];
                let t = (x - x1) / (x2 - x1);
                let d1 = derivatives[start];
                let d2 = derivatives[start + 1];
                let y1 = self.spline(&values[start], input);
                let y2 = self.spline(&values[start + 1], input);
                let a = d1 * (x2 - x1) - (y2 - y1);
                let b = -d2 * (x2 - x1) + (y2 - y1);
                lerp(t, y1, y2) + t * (1.0 - t) * lerp(t, a, b)
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn interpolate_block_step(
        &mut self,
        input: NodeId,
        cx: i32,
        cy: i32,
        inv_xz: f32,
        inv_y: f32,
        out: &mut [f32],
        vol: &DensityVolume,
    ) {
        let min_cell_x = vol.min_x.div_euclid(cx);
        let min_cell_y = vol.min_y.div_euclid(cy);
        let min_cell_z = vol.min_z.div_euclid(cx);
        let max_cell_x = vol.max_block_x().div_euclid(cx);
        let max_cell_y = vol.max_block_y().div_euclid(cy);
        let max_cell_z = vol.max_block_z().div_euclid(cx);
        let count_x = max_cell_x - min_cell_x + 1;
        let count_y = max_cell_y - min_cell_y + 1;
        let count_z = max_cell_z - min_cell_z + 1;
        let cell_volume = DensityVolume::new(
            [
                if vol.max_block_x().rem_euclid(cx) == 0 { count_x } else { count_x + 1 },
                if vol.max_block_y().rem_euclid(cy) == 0 { count_y } else { count_y + 1 },
                if vol.max_block_z().rem_euclid(cx) == 0 { count_z } else { count_z + 1 },
            ],
            [min_cell_x * cx, min_cell_y * cy, min_cell_z * cx],
            [cx, cy, cx],
        );
        let cb = self.sample_volume_new(input, &cell_volume);
        let at = |x: i32, y: i32, z: i32| cb[cell_volume.index_unchecked(x, y, z)];
        for cell_z in 0..count_z {
            let next_z = (cell_z + 1).min(cell_volume.size_z - 1);
            for cell_x in 0..count_x {
                let next_x = (cell_x + 1).min(cell_volume.size_x - 1);
                let mut v000 = at(cell_x, 0, cell_z);
                let mut v100 = at(next_x, 0, cell_z);
                let mut v001 = at(cell_x, 0, next_z);
                let mut v101 = at(next_x, 0, next_z);
                for cell_y in 0..count_y {
                    let next_y = (cell_y + 1).min(cell_volume.size_y - 1);
                    let v010 = at(cell_x, next_y, cell_z);
                    let v110 = at(next_x, next_y, cell_z);
                    let v011 = at(cell_x, next_y, next_z);
                    let v111 = at(next_x, next_y, next_z);
                    fill_cell(
                        out,
                        vol,
                        &cell_volume,
                        [cell_x, cell_y, cell_z],
                        [cx, cy],
                        [inv_xz, inv_y],
                        [v000, v100, v010, v110, v001, v101, v011, v111],
                    );
                    v000 = v010;
                    v100 = v110;
                    v001 = v011;
                    v101 = v111;
                }
            }
        }
        self.release(cb);
    }
}

/// `Mth.fastInvSqrt`
fn fast_inv_sqrt(mut x: f64) -> f64 {
    let half = 0.5 * x;
    let i = 6910469410427058090i64.wrapping_sub(x.to_bits() as i64 >> 1);
    x = f64::from_bits(i as u64);
    x * (1.5 - half * x * x)
}

fn bury_contribution(dx: f32, dy: f32, dz: f32) -> f32 {
    let distance_sq = dx * dx + dy * dy + dz * dz;
    if distance_sq >= 36.0 {
        0.0
    } else {
        1.0 - (distance_sq as f64).sqrt() as f32 / 6.0
    }
}

fn beard_contribution(kernel: &[f32], dx: i32, dy: i32, dz: i32, y_to_ground: i32) -> f32 {
    let (xi, yi, zi) = (dx + 12, dy + 12, dz + 12);
    let in_range = |v: i32| (0..24).contains(&v);
    if in_range(xi) && in_range(yi) && in_range(zi) {
        let dy_offset = y_to_ground as f32 + 0.5;
        let (fx, fz) = (dx as f32, dz as f32);
        let distance_sqr = fx * fx + dy_offset * dy_offset + fz * fz;
        let value = -dy_offset * fast_inv_sqrt((distance_sqr / 2.0) as f64) as f32 / 2.0;
        value * kernel[(zi * 24 * 24 + xi * 24 + yi) as usize]
    } else {
        0.0
    }
}

/// `Beardifier.sampleValueUnchecked`
fn beard_value(beard: &Beard, kernel: &[f32], x: i32, y: i32, z: i32) -> f32 {
    let mut value = 0.0f32;
    for rigid in &beard.pieces {
        let (min, max) = (rigid.min, rigid.max);
        let dx = 0.max((min[0] - x).max(x - max[0]));
        let dz = 0.max((min[2] - z).max(z - max[2]));
        let ground_y = min[1] + rigid.ground_level_delta;
        let dy_to_ground = y - ground_y;
        let dy = match rigid.adjustment {
            TerrainAdjustment::None => 0,
            TerrainAdjustment::Bury | TerrainAdjustment::BeardThin => dy_to_ground,
            TerrainAdjustment::BeardBox => 0.max((ground_y - y).max(y - max[1])),
            TerrainAdjustment::Encapsulate => 0.max((min[1] - y).max(y - max[1])),
        };
        value += match rigid.adjustment {
            TerrainAdjustment::None => 0.0,
            TerrainAdjustment::Bury => bury_contribution(dx as f32, dy as f32 / 2.0, dz as f32),
            TerrainAdjustment::BeardThin | TerrainAdjustment::BeardBox => {
                beard_contribution(kernel, dx, dy, dz, dy_to_ground) * 0.8
            }
            TerrainAdjustment::Encapsulate => {
                bury_contribution(dx as f32 / 2.0, dy as f32 / 2.0, dz as f32 / 2.0) * 0.8
            }
        };
    }
    for j in &beard.junctions {
        let (dx, dy, dz) = (x - j[0], y - j[1], z - j[2]);
        value += beard_contribution(kernel, dx, dy, dz, dy) * 0.4;
    }
    value
}

fn select_index(thresholds: &[f32], sampler_count: usize, input: f32) -> usize {
    for (i, t) in thresholds.iter().enumerate() {
        if input < *t {
            return i;
        }
    }
    sampler_count - 1
}

fn round_multiple(t: RoundType, input: f32, multiple: f32) -> f32 {
    if multiple == 0.0 {
        input
    } else {
        round_to_integer(input / multiple, t) * multiple
    }
}

/// `InterpolatedFunction.Sampler.fillCell`
fn fill_cell(
    out: &mut [f32],
    ov: &DensityVolume,
    cv: &DensityVolume,
    cell: [i32; 3],
    size: [i32; 2],
    inv: [f32; 2],
    v: [f32; 8],
) {
    let [v000, v100, v010, v110, v001, v101, v011, v111] = v;
    let ox = cv.block_x(cell[0]) - ov.min_x;
    let oy = cv.block_y(cell[1]) - ov.min_y;
    let oz = cv.block_z(cell[2]) - ov.min_z;
    let x0 = 0.max(-ox);
    let y0 = 0.max(-oy);
    let z0 = 0.max(-oz);
    let x1 = size[0].min(ov.size_x - ox) - 1;
    let y1 = size[1].min(ov.size_y - oy) - 1;
    let z1 = size[0].min(ov.size_z - oz) - 1;
    for z in z0..=z1 {
        let out_z = oz + z;
        let alpha_z = z as f32 * inv[0];
        let v00 = lerp(alpha_z, v000, v001);
        let v01 = lerp(alpha_z, v010, v011);
        let v10 = lerp(alpha_z, v100, v101);
        let v11 = lerp(alpha_z, v110, v111);
        for x in x0..=x1 {
            let out_x = ox + x;
            let alpha_x = x as f32 * inv[0];
            let v0 = lerp(alpha_x, v00, v10);
            let v1 = lerp(alpha_x, v01, v11);
            let step = (v1 - v0) * inv[1];
            let mut value = v0 + step * y0 as f32;
            let mut index = ov.index_unchecked(out_x, oy + y0, out_z);
            for _ in y0..=y1 {
                out[index] = value;
                index += 1;
                value += step;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_round_matches_java() {
        // Values from Math.round(float) in Java.
        for (input, expected) in [
            (0.5f32, 1),
            (-0.5, 0),
            (0.49999997, 0),
            (1.5, 2),
            (-1.5, -1),
            (2.5, 3),
            (-2.5, -2),
            (1e10, i32::MAX),
            (-1e10, i32::MIN),
            (f32::NAN, 0),
        ] {
            assert_eq!(java_round(input), expected, "{input}");
        }
    }

    #[test]
    fn binary_search_like_mth() {
        let locations = [-1.0f32, 0.0, 1.0];
        assert_eq!(find_interval_start(&locations, -2.0), -1);
        assert_eq!(find_interval_start(&locations, -1.0), 0);
        assert_eq!(find_interval_start(&locations, 0.5), 1);
        assert_eq!(find_interval_start(&locations, 1.0), 2);
        assert_eq!(find_interval_start(&locations, f32::NAN), 2);
    }
}
