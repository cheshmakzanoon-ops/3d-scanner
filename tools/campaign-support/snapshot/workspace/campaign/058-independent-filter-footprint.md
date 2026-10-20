# Independent finite mip footprint review

Source: `2f18a4f5a87139c38ee388f6c70af33e0fb247f7`. This is a read-only
mathematical qualification of specified HOST filters, not a change to the
production sampler. The frozen GLB sampler remains LINEAR without mipmaps.

For each atlas axis, work in the actual cropped image coordinates. A crop with
origin c requires subtracting c from rectangle coordinates first; the current
057 crop origin is zero and only removes unused right/bottom rows. Let N0 be the
cropped dimension and Nl=max(1,floor(N0/2^l)). With normalized coordinate u,
bilinear level-l texel coordinate is q=u*Nl-1/2. The two potentially contributing
indices are floor(q) and floor(q)+1, each clamped to [0,Nl-1]. Including a
zero-coefficient endpoint is conservative.

## Exact normalized-area box ancestry

Let A_l(j), B_l(j) be the first/last base-image pixel with positive ancestry in
mip texel j. A_0(j)=B_0(j)=j. For the normalized-area box from dimensions P to M:

    A_l(j) = A_(l-1)(floor(j*P/M))
    B_l(j) = B_(l-1)(ceil((j+1)*P/M)-1)

Use integer division and integer ceil. These intervals are monotone and include
all contributing pixels; no power-of-two alignment assumption is made. The
bilinear base ancestry is the interval from A_l(clamp(floor(q))) through
B_l(clamp(floor(q)+1)). The 2D filter is the Cartesian product of the axis
intervals. This exact oracle handles odd global dimensions, arbitrary packed
rectangle starts, NPOT crops and axes which have already reached dimension one.

A conservative closed bound follows by induction. Let S_l=N0/Nl and E_0=0:

    E_l = E_(l-1) + S_(l-1)*(1-gcd(N_(l-1),Nl)/Nl)

Then A_l(j)>=S_l*j-E_l and B_l(j)+1<=S_l*(j+1)+E_l. At an integer base boundary
sample m=u*N0-1/2, either side's ancestry reaches at most

    R_l = floor(3*S_l/2 + E_l - 1/2)

base pixels from m. Global clamp can only reduce this bound. For a power-of-two
atlas, E_l=0 and this gives margins 2, 5 and 11 for levels 1, 2 and 3. For an odd
first half-size N0=2M+1, M>=2, the expression before floor is 3.5+0.5/M, so the
level-one margin is at most 3. Smaller dimensions can be checked directly.

## Common 2x2 drop-last ancestry

For recursive 2x2 box/drop-last, define h=2^min(l,floor(log2(N0))). Once an axis
is one texel it stops expanding. A_l(j)=h*j and B_l(j)=h*(j+1)-1. Use the actual
Nl in normalized q, not N0/h without accounting for remainders. For active
levels l>=1 with h>=2, write d=N0-h*Nl; a conservative integer-boundary left reach is
3h/2+d-1 and right reach is 3h/2-1. Thus level one needs at most 3 for an odd
dimension, and at most 2 for an even dimension. Deeper NPOT levels must use the
dimension/remainder or exact ancestry, rather than the POT margins alone.
Level zero has the usual conservative one-base-pixel bilinear reach; a one-texel
axis is clamped to its sole texel.

## Rectangle isolation and trilinear filtering

The frozen layout maps every triangle into the rectangle's base-center content
interval [x+padding, x+width-padding-1], and similarly in y. This follows from
the +1 and ceil in packed dimensions and the +1/2 in normalized UVs. Cropping
changes normalization but preserves these base coordinates. The filter ancestry
is monotone in the sample coordinate, so checking the content interval endpoints
suffices. If both axis reaches are <=padding, every contributing base pixel is
in that chart's disjoint packed rectangle. No other chart can contribute color.
Unknown/transparent pixels inside the same rectangle do not invalidate this
cross-chart exclusion result.

Trilinear interpolation is a nonnegative combination of two bilinear mip samples;
it introduces no additional ancestry. A declared maxLOD needs every participating
level through ceil(maxLOD) qualified. A finite gutter does not qualify arbitrary
levels, anisotropic sampling or an unspecified GPU mip generation algorithm.

Default padding 3 therefore qualifies maxLOD=1 for the two specified box models,
including odd crops and arbitrary integer chart phase. Padding 2 does not provide
that universal NPOT guarantee. Deeper levels require the actual dimensions and
oracle, or larger justified padding. For example, an area-box 15-pixel axis has
actual worst level-two reaches 7 and 8; a 46-pixel axis reaches 7 on each side.

## Alpha holes and numerical scope

Cross-chart exclusion is not a promise that a small alpha-zero hole remains
alpha zero. A positive box/bilinear/trilinear filter averages neighboring alpha.
It remains zero exactly when every positive-weight base ancestor has zero alpha.
Claims about preserved holes therefore require the footprint to lie wholly in
the zero-alpha region; neither gutters nor this proof authorize inpainting or
claim zero holes survive all scales.

The accompanying JSON records exact-integer enumeration over every boundary
phase for dimensions 6..4096: 8,390,641 samples per filter model. Level-one reach
never exceeded 3. Repeating with the normalized boundary UV stored as float32
also never exceeded 3. Actual producer tests should use serialized cropped UVs
and the exact ancestry oracle, rather than assume ideal floating-point phases.
No repository source/test file, candidate, threshold, runtime profile or real
photograph was changed or measured for this review.
