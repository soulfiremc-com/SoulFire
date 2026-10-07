SoulFire Python SDK
===================

The API reference documents the SDK package, public modules, and generated protocol types.
Use the navigation or search to find a class, method, or type.
Each class has its own page with method signatures, attributes, and source links.

Start with the `Python tutorial <https://soulfiremc.com/docs/sdk/python>`_.
Use the `SDK recipes <https://soulfiremc.com/docs/sdk/recipes>`_ for complete programs.

SDK operations are lazy. Methods decorated with ``@fn`` return ``Effect`` values when called.
Their source signatures use ``EffectGen`` to describe the generator implementation.
Keep bot operations inside a scope and run the complete workflow at its boundary.

.. toctree::
   :maxdepth: 2
   :caption: Reference

   api/index

.. toctree::
   :caption: Guides

   Python tutorial <https://soulfiremc.com/docs/sdk/python>
   SDK recipes <https://soulfiremc.com/docs/sdk/recipes>
   TypeScript API <https://ts.soulfiremc.com/>
