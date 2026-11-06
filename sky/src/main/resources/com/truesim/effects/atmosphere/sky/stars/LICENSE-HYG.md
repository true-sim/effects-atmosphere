# Star catalogue: licence and attribution

`hyg-v41-mag7.csv` is derived from the **HYG Database v4.1** by David Nash (astronexus),
<https://github.com/astronexus/HYG-Database>, file `hyg/CURRENT/hygdata_v41.csv` at commit
`3bf37f4b2d5460e1278286320d1d62fab9b493c1`.

The HYG Database is licensed under the **Creative Commons Attribution-ShareAlike 4.0 International License**
(CC BY-SA 4.0), <https://creativecommons.org/licenses/by-sa/4.0/>; its licence file is copied unchanged as
`LICENSE-HYG-original.md`. HYG itself compiles the Hipparcos, Yale Bright Star and Gliese catalogues.

**Changes made:** only stars of visual magnitude 7.0 or brighter are kept, the Sun (HYG id 0) is removed, and only the
columns `id` (renamed `hyg`), `hip`, `hr`, `bf`, `proper`, `con`, `ra`, `dec`, `pmra`, `pmdec`, `mag` and `ci` are kept;
values are copied unchanged. The derivation is `tools/hyg_subset.py` in the effects-atmosphere repository.

This derived file is licensed under **CC BY-SA 4.0** as well. The licence covers this data file only, not the code that
reads it.
