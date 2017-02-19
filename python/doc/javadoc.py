
import os
import docutils, sphinx
import javalang
import re


ast_cache = {}


# Sphinx application API
# www.sphinx-doc.org/en/1.5.1/extdev/appapi.html

def setup(app):

	# configs
	app.add_config_value('javadoc_sources_dir', '.', 'env')
	app.add_config_value('javadoc_package_prefix', '', 'env')

	app.add_domain(JavadocDomain)

	app.add_role('java:fielddoc', FielddocRole())
	app.add_role('java:methoddoc', MethoddocRole())

	app.connect('autodoc-process-signature', autodoc_signature_handler)
	app.connect('autodoc-process-docstring', autodoc_docstring_handler)

	return { 'version': '0.1' }


def autodoc_signature_handler(app, what, name, obj, options, signature, return_annotation):

	# get the function arg info
	try:
		argspec = sphinx.util.inspect.getargspec(obj)
	except TypeError:
		# not a function, don't mess with the signature
		return

	# see if there are any defaults to override
	if argspec.defaults is None:
		return

	# convenience method for warnings
	def warn(msg, line=None):
		modname = obj.__module__
		srcpath = sphinx.pycode.ModuleAnalyzer.for_module(modname).srcname
		loc = '%s:docstring of %s.%s' % (srcpath, modname, obj.__name__)
		if line is not None:
			loc += ':%d' % line
		app.warn(msg, location=loc)

	# convert to a mutable format (argspecs are not editable by default)
	argspec = argspec._asdict()
	names = argspec['args']
	defaults = list(argspec['defaults'])
	argspec['defaults'] = defaults

	# write defaults without quoting strings
	class Literal():
		def __init__(self, val):
			self.val = val
		def __repr__(self):
			return self.val

	def set_default(name, val):
		offset = len(names) - len(defaults)
		for i in range(len(defaults)):
			if names[offset + i] == name:
				defaults[i] = Literal(val)
				return
		raise KeyError

	def parse_default(text):
		# TODO: resolve references
		return text

	# look for default value commands in the docstring, like:
	# :default argname: default value
	doclines = obj.__doc__.split('\n')
	for i in range(len(doclines)):

		line = doclines[i].strip()

		if line.startswith(':default'):
			line_parts = line.split(':')
			if len(line_parts) >= 3:
				name_parts = line_parts[1].split(' ')
				if len(name_parts) >= 2:

					name = name_parts[1].strip()
					value = line_parts[2].strip()

					# found one, set the new default!
					try:
						set_default(name, parse_default(value))
					except KeyError:
						warn("no arg with name '%s'" % name, line=i)

	# build the new signature from the modified argspec
	return (sphinx.ext.autodoc.formatargspec(obj, *argspec.values()), None)


def autodoc_docstring_handler(app, what, name, obj, options, lines):

	# strip out default values in the docstring
	# (make sure we change the original list object)
	lines[:] = [line for line in lines if not line.strip().startswith(':default')]


def expand_classname(classname, config):
	
	# expand the classname if we need
	if classname[0] == '.':
		classname = config.javadoc_package_prefix + classname

	return classname


class JavaRef():
	
	def __init__(self, target, membername=None):

		# accepts javadoc style targets, eg:
		# package.Class
		# package.Class#member
		# package.Class$Inner
		# package.Class$Inner$Inner#member

		# 'full classnames' are fully qualified, eg:
		# package.Class
		# package.Class$Inner

		# 'classnames' have no packages, but have nesting chains, eg:
		# Class
		# Class$Inner$Inner

		# 'simple classnames' are just the name of the class, eg:
		# Class
		# Inner

		# split off the package
		parts = target.split('.')
		if len(parts) > 1:
			self.package = '.'.join(parts[:-1])
			target = parts[-1]
		else:
			self.package = None

		# get the member name
		if membername is not None:
			self.membername = membername
		else:
			parts = target.split('#')
			if len(parts) > 1:
				self.membername = parts[1]
				target = parts[0]
			else:
				self.membername = None

		self.classname = target

		# parse the nested classes
		self.simple_classnames = self.classname.split('$')
		self.outer_classname = self.simple_classnames[0]
		self.simple_classname = self.simple_classnames[-1]
		self.simple_inner_classnames = self.simple_classnames[1:]

		# get the fully-qualified classnames
		if self.package is None:
			self.full_classname = None
			self.full_outer_classname = None
		else:
			self.full_classname = '.'.join([self.package, self.classname])
			self.full_outer_classname = '.'.join([self.package, self.outer_classname])
	

	def __str__(self):
		out = self.classname
		if self.package is not None:
			out = '%s.%s' % (self.package, out)
		if self.membername is not None:
			out = '%s#%s' % (out, self.membername)
		return out


	def unprefixed_full_classname(self, prefix):
		if self.full_classname.startswith(prefix):
			return self.full_classname[len(prefix) + 1:]
		else:
			return self.full_classname

	
	def source_file(self):
		return '%s.java' % self.full_outer_classname.replace('.', '/')


class ImportResolver():

	def __init__(self, imports):
		self.imports = imports

	
	def resolve(self, target):

		ref = JavaRef(target)

		for imp in self.imports:

			# does this import resolve the target?
			if imp.path.endswith('.%s' % ref.simple_classname):
				return JavaRef(imp.path, membername=ref.membername)

		raise ValueError("can't resolve java reference against imports: '%s'" % target)


def read_file(path):
	with open(path, 'r') as file:
		return file.read()


def get_class_ast(ref, config):

	# convert ref to a filepath
	path = os.path.join(config.javadoc_sources_dir, ref.source_file())

	# read syntax tree from the cache, or from the file
	try:
		ast = ast_cache[path]
	except KeyError:
		ast = javalang.parse.parse(read_file(path))
		ast_cache[path] = ast

	# save imports for later
	imports = ast.imports

	def find_type(name, classtypes):
		for classtype in classtypes:
			if classtype.name == name:
				return classtype
		raise KeyError
	
	# get the root type in the compilation unit
	try:
		ast = find_type(ref.outer_classname, ast.types)
	except KeyError:
		raise KeyError("can't find outer class %s in source file %s" % (ref.outer_classname, ref.source_file()))
	
	# get nested types if needed
	for name in ref.simple_inner_classnames:
		subtypes = [member for member in ast.body if isinstance(member, javalang.tree.TypeDeclaration)]
		try:
			ast = find_type(name, subtypes)
		except KeyError:
			raise KeyError("can't find inner class %s in outer class %s in source file %s" % (name, ast.name, ref.source_file()))
	
	# reference the imports in the returned type
	ast.imports = imports

	# add some helper methods
	def find_field(name):
		for field in ast.fields:
			for declarator in field.declarators:
				if declarator.name == name:
					return field
		raise KeyError("can't find field: %s" % name)
	ast.find_field = find_field

	def find_method(name):
		for method in ast.methods:
			if method.name == name:
				return method
		raise KeyError("can't find method: %s" % name)
	ast.find_method = find_method

	return ast


class Javadoc():

	def __init__(self, text, imports):

		if text is None:
			raise ValueError('javadoc cannot be None')

		self.text = text
		self.import_resolver = ImportResolver(imports)
		self.parsed = javalang.javadoc.parse(text)


	@property
	def description(self):
		return self._translate(self.parsed.description)


	def _translate(self, text):

		# translate links from javadoc to sphinx
		link_regex = re.compile(r'\{@link ([^\}]+)\}')
		def link_resolver(match):
			
			# what's being linked to?
			target = match.group(1)

			# resolve against imports to get a full ref
			ref = self.import_resolver.resolve(target)

			# build the rst
			rst = []
			rst.append(':java:ref:`%s`' % str(ref))
			return '\n'.join(rst)

		return link_regex.sub(link_resolver, text)


def is_public(thing):
	return 'public' in thing.modifiers


def should_show(thing):
	return thing.documentation is not None and is_public(thing)


def make_method_signature(method):
	return method.name


def parse_rst(rst, settings):
	
	# if rst is a list of strings, join it
	if not isinstance(rst, str):
		rst = '\n'.join(rst)

	# parse the rst and return the nodes
	docSource = 'I come from the water'
	doc = docutils.utils.new_document(docSource, settings)
	docutils.parsers.rst.Parser().parse(rst, doc)

	# if there's just a single paragraph node at the root of the doc, unwrap it
	# so our parsed rst can be placed inline with existing rst
	if len(doc.children) == 1 and isinstance(doc.children[0], docutils.nodes.paragraph):
		return doc.children[0].children
	
	return doc.children


class ParsingDirective(docutils.parsers.rst.Directive):

	@property
	def env(self):
		return self.state.document.settings.env


	@property
	def config(self):
		return self.env.config


	def parse(self, rst):
		return parse_rst(rst, self.state.document.settings)


class JavaClassDirective(ParsingDirective):
	
	has_content = True

	def run(self):

		ref = JavaRef(expand_classname(self.content[0], self.config))
		ast = get_class_ast(ref, self.config)

		rst = []

		# class name header
		rst.append(ast.name)
		rst.append('=' * len(ast.name))

		rst.append('')

		showedSomething = False

		# show fields
		fields = [field for field in ast.fields if should_show(field)]
		if len(fields) > 0:

			# show fields
			rst.append('Properties')
			rst.append('----------')
			rst.append('')
			showedSomething = True

			for field in fields:

				# show the field name and javadoc
				for decl in field.declarators:
					rst.append('.. py:attribute:: %s' % decl.name)
					rst.append('')
					if field.documentation is not None:
						rst.append('\t' + Javadoc(field.documentation, ast.imports).description)
						rst.append('')

		# show methods
		methods = [method for method in ast.methods if should_show(method)]
		if len(methods) > 0:

			rst.append('Methods')
			rst.append('-------')
			rst.append('')
			showedSomething = True

			for method in methods:

				# show the method signature and javadoc
				rst.append('.. py:method:: %s' % make_method_signature(method))
				rst.append('')
				if method.documentation is not None:
					rst.append('\t' + Javadoc(method.documentation, ast.imports).description)
					rst.append('')

		# show enum constants
		if isinstance(ast, javalang.tree.EnumDeclaration):

			rst.append('Constants')
			rst.append('-----------')
			rst.append('')
			showedSomething = True

			for value in ast.body.constants:
				
				# show the value name and javadoc
				rst.append('.. py:attribute:: %s' % value.name)
				rst.append('')
				if value.documentation is not None:
					rst.append('\t' + Javadoc(value.documentation, ast.imports).description)
					rst.append('')

		if not showedSomething:

			rst.append('*(This topic does not yet have documentation)*')

		return self.parse(rst)


# how to write function roles:
# http://docutils.sourceforge.net/docs/howto/rst-roles.html

class JavaRole():

	def __call__(self, name, rawtext, text, lineno, inliner, options={}, content=[]):

		settings = inliner.document.settings

		# add some convenience attributes for subclasses
		self.config = settings.env.config
	
		# add some convenience methods for subclasses
		def warn(msg, cause=None):

			# format the warning for rst
			rst = '*(%s)*' % msg

			# add cause info for the console
			if cause is not None:
				msg += '\n\tcause: ' + str(cause)

			inliner.reporter.warning(msg, line=lineno)

			return rst

		self.warn = warn

		# parse the rst and return docutils nodes
		return parse_rst(self.make_rst(text), settings), []


	def make_rst(self, text):
		raise Exception('implement me for %s' % self.__class__.__name__)


class FielddocRole(JavaRole):

	def make_rst(self, text):

		# find the field
		try:
			ref = JavaRef(expand_classname(text, self.config))
			ast = get_class_ast(ref, self.config)
			field = ast.find_field(ref.membername)
		except (KeyError, ValueError, FileNotFoundError) as e:
			return self.warn("can't find field: %s" % text, cause=e)
		
		# look for the javadoc
		if field.documentation is None:
			return self.warn("field %s has no javadoc" % ref)

		# parse the javadoc
		try:
			return Javadoc(field.documentation, ast.imports).description
		except (KeyError, ValueError) as e:
			return self.warn("can't parse javadoc for field: %s" % ref, cause=e)


class MethoddocRole(JavaRole):

	def make_rst(self, text):

		# find the method
		try:
			ref = JavaRef(expand_classname(text, self.config))
			ast = get_class_ast(ref, self.config)
			method = ast.find_method(ref.membername)
		except (KeyError, ValueError, FileNotFoundError) as e:
			return self.warn("can't find method: %s" % text, cause=e)

		# look for the javadoc
		if method.documentation is None:
			return self.warn("method %s has no javadoc" % ref)

		# parse the javadoc
		try:
			return Javadoc(method.documentation, ast.imports).description
		except (KeyError, ValueError) as e:
			return self.warn("can't parse javadoc for method: %s" % ref, cause=e)


class RefRole(sphinx.roles.XRefRole):

	def __call__(self, typ, rawtext, text, lineno, inliner, options={}, content=[]):

		# get the reporter from the inliner
		# so the warnings have the correct source info when using autodoc
		self.reporter = inliner.reporter

		# then make a convenience method for warnings
		def warn(msg):
			self.reporter.warning(msg, line=lineno)
		self.warn = warn

		return sphinx.roles.XRefRole.__call__(self, typ, rawtext, text, lineno, inliner, options, content)


	def process_link(self, env, refnode, has_explicit_title, title, target):

		# resolve the target
		ref = JavaRef(expand_classname(target, env.config))
		resolved = ResolvedXref()
		resolved.docpath = 'api.' + ref.unprefixed_full_classname(env.config.javadoc_package_prefix)
		resolved.docpath = resolved.docpath.replace('$', '.')

		if ref.membername is not None:
			resolved.text = ref.membername
			resolved.anchor = ref.membername
		else:
			resolved.text = ref.simple_classname

		# see if the docpath exists
		path = env.doc2path(resolved.docpath)
		if not os.path.exists(path):
			self.warn('cross-reference does not exist: %s' % path)

		# attach the resolution to the node so we can find it again in Domain.resolve_xref()
		refnode.resolved = resolved

		return title, target
	

class ResolvedXref():

	def __init__(self):
		self.docpath = None
		self.text = None
		self.anchor = ''
		self.title = None


# Sphinx domain API:
# http://www.sphinx-doc.org/en/1.5.1/extdev/domainapi.html

class JavadocDomain(sphinx.domains.Domain):

	name = 'java'
	label = 'Osprey javadoc processor'

	directives = {
		'class': JavaClassDirective
	}

	roles = {
		'ref': RefRole()
	}


	def resolve_xref(self, env, fromdocname, builder, reftype, target, node, contnode):

		# get the ref resolution from our JavaRole
		resolved = node.resolved

		# set the link text in the node thingy
		contnode.replace(
			contnode.children[0],
			docutils.nodes.Text(resolved.text)
		)

		# return the ref node
		return sphinx.util.nodes.make_refnode(
			builder,
			fromdocname,
			resolved.docpath,
			resolved.anchor,
			contnode,
			resolved.title
		)
 
