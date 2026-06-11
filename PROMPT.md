audit the pov renderer in this app. it's a rasterization renderer. could you analyze our usage of joml, the math, the constraints, the official blaze3d code, the opengl spec, everything and fix issues with this pov custom software renderer compared to the official opengl based
blaze3d rendering pipeline. ofc also look at mc code. also fix most visual/unimplemented regressions. e.g. submit collector missing mc edge cases. all about the edge cases. you should idiomatically, thoroughly and without backwards support rewrite and redesign pov renderer code
where necessary.
commit and push once done
